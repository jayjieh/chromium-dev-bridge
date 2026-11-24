package dev.cdb.provider.playwright;

import dev.cdb.core.BridgeException;
import dev.cdb.core.spi.BrowserEventListener;
import dev.cdb.core.spi.BrowserProvider;
import dev.cdb.core.spi.BrowserSessionDriver;
import dev.cdb.core.spi.SessionLaunchSpec;
import dev.cdb.protocol.ErrorCode;
import dev.cdb.protocol.ProviderInfo;
import dev.cdb.protocol.SessionIsolation;
import dev.cdb.provider.playwright.discovery.BrowserDiscovery;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Browser provider backed by Playwright for Java. */
public class PlaywrightProvider implements BrowserProvider {
    private static final Logger log = LoggerFactory.getLogger(PlaywrightProvider.class);
    public static final String ID = "playwright-chromium";

    private final PlaywrightSettings settings;
    private final Map<String, PlaywrightWorker> sharedWorkers = new ConcurrentHashMap<>();
    private final AtomicBoolean installed = new AtomicBoolean();
    private volatile String browserVersion;

    public PlaywrightProvider(PlaywrightSettings settings) {
        this.settings = settings;
    }

    @Override
    public ProviderInfo info() {
        return new ProviderInfo(ID, "Playwright Chromium", playwrightVersion(), "chromium", browserVersion);
    }

    static String playwrightVersion() {
        Package p = com.microsoft.playwright.Playwright.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null ? "1.65.1" : v;
    }

    @Override
    public BrowserSessionDriver open(SessionLaunchSpec spec, BrowserEventListener listener) {
        ensureBrowserInstalled(spec);
        boolean dedicated = spec.isolation() == SessionIsolation.PROCESS || spec.profileDir() != null || spec.connectEndpoint() != null;
        PlaywrightWorker worker;
        if (dedicated) {
            worker = new PlaywrightWorker(spec.sessionId(), settings);
        } else {
            String key = (spec.headless() ? "headless" : "headed") + "-" + (spec.channel() == null ? "default" : spec.channel());
            worker = sharedWorkers.computeIfAbsent(key, k -> new PlaywrightWorker(k, settings));
        }
        try {
            PlaywrightSession session = worker.call(() -> PlaywrightSession.open(worker, dedicated, spec, listener, settings),
                    Duration.ofSeconds(120));
            browserVersion = session.providerInfo().browserVersion();
            return session;
        } catch (RuntimeException e) {
            if (dedicated) worker.close();
            if (e instanceof BridgeException) throw e;
            throw new BridgeException(ErrorCode.BROWSER_ERROR, PlaywrightErrors.firstLine(e), e);
        }
    }

    /** Downloads Playwright's Chromium once if no explicit executable/channel is configured. */
    private void ensureBrowserInstalled(SessionLaunchSpec spec) {
        if (!settings.installBrowser() || settings.executablePath() != null || settings.defaultChannel() != null
                || spec.connectEndpoint() != null || (spec.channel() != null && !spec.channel().equals("chromium"))) {
            return;
        }
        if (installed.get()) return;
        synchronized (installed) {
            if (installed.get()) return;
            BrowserInstaller.installChromium();
            installed.set(true);
        }
    }

    @Override
    public List<String> discoveredBrowsers() {
        List<String> out = new ArrayList<>();
        BrowserDiscovery.discover().forEach(b -> out.add(b.kind() + ": " + b.path()));
        return out;
    }

    void closeIdleWorker(PlaywrightWorker worker) {
        // Shared workers stay alive (fast next session); nothing to do.
    }

    @Override
    public void close() {
        for (PlaywrightWorker w : sharedWorkers.values()) {
            try {
                w.close();
            } catch (RuntimeException e) {
                log.debug("Worker close failed: {}", e.toString());
            }
        }
        sharedWorkers.clear();
    }
}
