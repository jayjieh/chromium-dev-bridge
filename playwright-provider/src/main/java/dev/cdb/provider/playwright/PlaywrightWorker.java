package dev.cdb.provider.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.cdb.core.BridgeException;
import dev.cdb.protocol.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one Playwright instance and its thread (Playwright for Java is not thread-safe). All Playwright calls for the
 * sessions on this worker run on this thread. When idle, the worker pumps events by briefly waiting on an open page.
 * See ADR-0002.
 */
final class PlaywrightWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PlaywrightWorker.class);
    static final String REF_ENGINE = """
            (() => ({
              query(root, ref) {
                const m = window.__cdbRefs;
                const w = m && m.get(ref);
                const el = w && (w.deref ? w.deref() : w);
                return el && el.isConnected && (root === document || root.contains(el)) ? el : null;
              },
              queryAll(root, ref) {
                const el = this.query(root, ref);
                return el ? [el] : [];
              }
            }))()
            """;

    private final String name;
    private final PlaywrightSettings settings;
    private final BlockingQueue<FutureTask<?>> queue = new LinkedBlockingQueue<>();
    private final List<PlaywrightSession> sessions = new CopyOnWriteArrayList<>();
    private final Thread thread;
    private volatile boolean running = true;
    private Playwright playwright;
    private Browser browser;

    PlaywrightWorker(String name, PlaywrightSettings settings) {
        this.name = name;
        this.settings = settings;
        this.thread = new Thread(this::loop, "cdb-playwright-" + name);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    String name() {
        return name;
    }

    boolean isWorkerThread() {
        return Thread.currentThread() == thread;
    }

    /** Runs a task on the worker thread and waits for it. Re-entrant when called from the worker thread. */
    <T> T call(Callable<T> task, Duration timeout) {
        if (isWorkerThread()) {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new BridgeException(ErrorCode.BROWSER_ERROR, e.getMessage(), e);
            }
        }
        if (!running) throw new BridgeException(ErrorCode.SESSION_NOT_RUNNING, "Browser worker " + name + " is stopped");
        FutureTask<T> f = new FutureTask<>(task);
        queue.add(f);
        try {
            return f.get(timeout.toMillis() + 5_000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(false);
            throw new BridgeException(ErrorCode.TIMEOUT, "Browser operation timed out after " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BridgeException(ErrorCode.INTERNAL, "Interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) throw re;
            throw new BridgeException(ErrorCode.BROWSER_ERROR, c.getMessage(), c);
        }
    }

    void register(PlaywrightSession s) {
        sessions.add(s);
    }

    void unregister(PlaywrightSession s) {
        sessions.remove(s);
    }

    int sessionCount() {
        return sessions.size();
    }

    // ---- called on the worker thread only

    Playwright playwright() {
        if (playwright == null) {
            Playwright.CreateOptions opts = new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
            playwright = Playwright.create(opts);
            playwright.selectors().register("cdbref", REF_ENGINE);
        }
        return playwright;
    }

    Browser sharedBrowser(boolean headless, String channel) {
        if (browser == null || !browser.isConnected()) {
            browser = launch(headless, channel);
        }
        return browser;
    }

    Browser launch(boolean headless, String channel) {
        BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(headless);
        if (settings.executablePath() != null) {
            opts.setExecutablePath(settings.executablePath());
        } else if (channel != null && !channel.equals("chromium")) {
            opts.setChannel(channel);
        } else if (settings.defaultChannel() != null) {
            opts.setChannel(settings.defaultChannel());
        }
        List<String> args = new ArrayList<>(settings.extraArgs());
        opts.setArgs(args);
        Browser b = playwright().chromium().launch(opts);
        b.onDisconnected(x -> {
            log.warn("Browser on worker {} disconnected", name);
            for (PlaywrightSession s : sessions) s.browserDisconnected();
        });
        log.info("Launched {} {} (headless={}) on worker {}", b.browserType().name(), b.version(), headless, name);
        return b;
    }

    private void loop() {
        long pumpMs = Math.max(5, settings.pumpInterval().toMillis());
        while (running) {
            try {
                FutureTask<?> task = queue.poll(pumpMs, TimeUnit.MILLISECONDS);
                if (task != null) {
                    task.run();
                    continue;
                }
                pump();
            } catch (InterruptedException e) {
                break;
            } catch (RuntimeException e) {
                log.debug("Worker {} loop error: {}", name, e.toString());
            }
        }
        shutdownPlaywright();
    }

    /** Lets Playwright dispatch pending events (console, network) while no operation is running. */
    private void pump() {
        for (PlaywrightSession s : sessions) s.tick();
        for (PlaywrightSession s : sessions) {
            Page p = s.anyOpenPage();
            if (p != null) {
                try {
                    p.waitForTimeout(1);
                } catch (RuntimeException e) {
                    log.trace("Pump failed: {}", e.toString());
                }
                return;
            }
        }
    }

    private void shutdownPlaywright() {
        try {
            if (browser != null && browser.isConnected()) browser.close();
        } catch (RuntimeException e) {
            log.debug("Browser close failed: {}", e.toString());
        }
        try {
            if (playwright != null) playwright.close();
        } catch (RuntimeException e) {
            log.debug("Playwright close failed: {}", e.toString());
        }
        playwright = null;
        browser = null;
    }

    @Override
    public void close() {
        if (!running) return;
        running = false;
        thread.interrupt();
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Fail queued tasks.
        FutureTask<?> t;
        while ((t = queue.poll()) != null) t.cancel(true);
    }
}
