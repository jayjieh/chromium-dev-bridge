package dev.cdb.provider.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.WebSocket;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.Timing;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import dev.cdb.core.BridgeException;
import dev.cdb.core.console.StackTraceParser;
import dev.cdb.core.spi.BrowserEventListener;
import dev.cdb.core.spi.BrowserEventListener.NavigationPhase;
import dev.cdb.core.spi.BrowserSessionDriver;
import dev.cdb.core.spi.CapturedImage;
import dev.cdb.core.spi.SessionLaunchSpec;
import dev.cdb.protocol.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One browser context with tabs. Every public method hops onto the owning worker thread. Playwright objects never
 * leave that thread; event callbacks copy plain data into protocol records.
 */
final class PlaywrightSession implements BrowserSessionDriver {
    private static final Logger log = LoggerFactory.getLogger(PlaywrightSession.class);
    private static final Pattern LOCATION = Pattern.compile("^(.*):(\\d+):(\\d+)$");
    private static final String SENSITIVE_MASK_SELECTOR = "input[type=password], input[autocomplete*=cc-], "
            + "input[autocomplete=one-time-code], [data-cdb-sensitive]";

    private final PlaywrightWorker worker;
    private final boolean dedicated;
    private final SessionLaunchSpec spec;
    private final BrowserEventListener listener;
    private final PlaywrightSettings settings;
    private final String contextId = "ctx-" + UUID.randomUUID().toString().substring(0, 8);
    private final Map<String, Page> tabs = new LinkedHashMap<>();
    private final Map<Page, String> tabIds = new IdentityHashMap<>();
    private final Map<Request, String> requestIds = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, Response> responses = new LinkedHashMap<>();
    private final Map<String, Instant> requestStarts = new LinkedHashMap<>();
    private final List<PendingError> pendingErrors = new ArrayList<>();
    private final java.util.Set<String> blockedTabs = new java.util.LinkedHashSet<>();
    private final AtomicInteger tabSeq = new AtomicInteger();
    private final AtomicInteger requestSeq = new AtomicInteger();
    private Browser browser;
    private BrowserContext context;
    private String activeTabId;
    private List<String> lastUrls = List.of();
    private volatile boolean connected = true;
    private volatile boolean closed;
    private String browserVersion;

    private record PendingError(String tabId, String message, String stack, Instant at) { }

    private PlaywrightSession(PlaywrightWorker worker, boolean dedicated, SessionLaunchSpec spec, BrowserEventListener listener,
                              PlaywrightSettings settings) {
        this.worker = worker;
        this.dedicated = dedicated;
        this.spec = spec;
        this.listener = listener;
        this.settings = settings;
    }

    /** Called on the worker thread. */
    static PlaywrightSession open(PlaywrightWorker worker, boolean dedicated, SessionLaunchSpec spec,
                                  BrowserEventListener listener, PlaywrightSettings settings) {
        PlaywrightSession s = new PlaywrightSession(worker, dedicated, spec, listener, settings);
        s.createContext();
        worker.register(s);
        return s;
    }

    // ------------------------------------------------------------------ setup (worker thread)

    private void createContext() {
        if (spec.connectEndpoint() != null) {
            browser = worker.playwright().chromium().connectOverCDP(spec.connectEndpoint());
            browser.onDisconnected(b -> browserDisconnected());
            context = browser.newContext(contextOptions());
        } else if (spec.profileDir() != null) {
            BrowserType.LaunchPersistentContextOptions o = new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(spec.headless())
                    .setViewportSize(spec.viewport().width(), spec.viewport().height())
                    .setAcceptDownloads(spec.acceptDownloads());
            if (spec.locale() != null) o.setLocale(spec.locale());
            if (spec.deviceScaleFactor() != null) o.setDeviceScaleFactor(spec.deviceScaleFactor());
            if (spec.userAgent() != null) o.setUserAgent(spec.userAgent());
            if (settings.executablePath() != null) o.setExecutablePath(settings.executablePath());
            else if (spec.channel() != null && !spec.channel().equals("chromium")) o.setChannel(spec.channel());
            if (spec.downloadDir() != null) o.setDownloadsPath(spec.downloadDir());
            context = worker.playwright().chromium().launchPersistentContext(spec.profileDir(), o);
            context.onClose(c -> {
                if (!closed) browserDisconnected();
            });
        } else {
            browser = dedicated ? worker.launch(spec.headless(), spec.channel()) : worker.sharedBrowser(spec.headless(), spec.channel());
            context = browser.newContext(contextOptions());
        }
        if (browser != null) browserVersion = browser.version();
        context.setDefaultTimeout(spec.operationTimeout().toMillis());
        context.setDefaultNavigationTimeout(spec.navigationTimeout().toMillis());
        context.exposeFunction("__cdbReportRejection", args -> {
            onRejection(args.length > 0 ? String.valueOf(args[0]) : "", args.length > 1 ? String.valueOf(args[1]) : "");
            return null;
        });
        context.addInitScript(Scripts.REJECTION_HOOK);
        if (settings.enforceNavigationGuard()) context.route("**/*", this::route);
        context.onPage(this::registerTab);
        context.onRequest(this::onRequest);
        context.onResponse(this::onResponse);
        context.onRequestFinished(this::onRequestFinished);
        context.onRequestFailed(this::onRequestFailed);
        List<Page> existing = context.pages();
        if (existing.isEmpty()) {
            context.newPage();
        } else {
            for (Page p : existing) {
                if (!tabIds.containsKey(p)) registerTab(p);
            }
        }
        if (activeTabId == null && !tabs.isEmpty()) activeTabId = tabs.keySet().iterator().next();
    }

    private Browser.NewContextOptions contextOptions() {
        Browser.NewContextOptions o = new Browser.NewContextOptions()
                .setViewportSize(spec.viewport().width(), spec.viewport().height())
                .setAcceptDownloads(spec.acceptDownloads());
        if (spec.locale() != null) o.setLocale(spec.locale());
        if (spec.deviceScaleFactor() != null) o.setDeviceScaleFactor(spec.deviceScaleFactor());
        if (spec.userAgent() != null) o.setUserAgent(spec.userAgent());
        return o;
    }

    private void registerTab(Page page) {
        if (tabIds.containsKey(page)) return;
        String id = "tab-" + tabSeq.incrementAndGet();
        tabs.put(id, page);
        tabIds.put(page, id);
        activeTabId = id;
        page.onConsoleMessage(m -> onConsole(id, m.type(), m.text(), m.location()));
        page.onPageError(err -> pendingErrors.add(new PendingError(id, errorMessage(err), err, Instant.now())));
        page.onLoad(p -> safeListener(() -> listener.onNavigation(id, p.url(), NavigationPhase.COMPLETED, null)));
        page.onCrash(p -> emitConsole(id, ConsoleLevel.ERROR, ConsoleKind.PAGE_ERROR, "Page crashed", null, List.of()));
        page.onDialog(d -> {
            emitConsole(id, ConsoleLevel.INFO, ConsoleKind.CONSOLE, "Dialog (" + d.type() + ") dismissed: " + d.message(), null, List.of());
            try {
                d.dismiss();
            } catch (RuntimeException ignored) {
                // already handled
            }
        });
        page.onWebSocket(ws -> onWebSocket(id, ws));
        page.onClose(p -> {
            tabs.remove(id);
            tabIds.remove(p);
            if (id.equals(activeTabId)) activeTabId = tabs.isEmpty() ? null : tabs.keySet().iterator().next();
            safeListener(() -> listener.onTabClosed(id));
        });
        safeListener(() -> listener.onTabCreated(new TabInfo(id, page.url(), "", true)));
    }

    // ------------------------------------------------------------------ capture (worker thread)

    private void route(Route route) {
        Request req = route.request();
        try {
            boolean mainFrameNavigation = req.isNavigationRequest() && isMainFrame(req);
            if (mainFrameNavigation && !spec.guard().isAllowed(req.url())) {
                String tab = tabOf(req);
                if (tab != null) blockedTabs.add(tab);
                safeListener(() -> listener.onNavigationBlocked(tab, req.url()));
                route.abort("blockedbyclient");
                return;
            }
            route.resume();
        } catch (RuntimeException e) {
            log.trace("Route handling failed: {}", e.toString());
        }
    }

    private static boolean isMainFrame(Request req) {
        try {
            Frame f = req.frame();
            return f != null && f.parentFrame() == null;
        } catch (RuntimeException e) {
            return false; // service worker requests have no frame
        }
    }

    private String tabOf(Request req) {
        try {
            Frame f = req.frame();
            return f == null ? null : tabIds.get(f.page());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String requestId(Request r) {
        return requestIds.computeIfAbsent(r, k -> "req-" + requestSeq.incrementAndGet());
    }

    private void onRequest(Request r) {
        String id = requestId(r);
        Instant now = Instant.now();
        requestStarts.put(id, now);
        trim(requestStarts);
        String body = null;
        if (spec.captureBodies()) {
            try {
                body = r.postData();
            } catch (RuntimeException ignored) {
                // binary bodies
            }
        }
        Request from = r.redirectedFrom();
        NetworkEntry e = new NetworkEntry(0, id, null, tabOf(r), r.method(), r.url(), r.resourceType(), NetworkState.PENDING,
                null, null, null, now, null, null, from == null ? null : from.url(), false, false, r.headers(), null, body, null);
        safeListener(() -> listener.onNetwork(e));
    }

    private void onResponse(Response resp) {
        String id = requestId(resp.request());
        responses.put(id, resp);
        trim(responses);
    }

    private void onRequestFinished(Request r) {
        String id = requestId(r);
        Response resp = responses.remove(id);
        Instant start = requestStarts.remove(id);
        Double duration = duration(r, start);
        String mime = null;
        Map<String, String> respHeaders = null;
        Integer status = null;
        String statusText = null;
        String body = null;
        if (resp != null) {
            status = resp.status();
            statusText = resp.statusText();
            respHeaders = resp.headers();
            mime = respHeaders.get("content-type");
            if (spec.captureBodies() && isTextual(mime)) {
                try {
                    byte[] bytes = resp.body();
                    if (bytes != null && bytes.length <= spec.maxBodyBytes() * 4L) {
                        body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    }
                } catch (RuntimeException ignored) {
                    // body unavailable (redirect, evicted)
                }
            }
        }
        Request from = r.redirectedFrom();
        NetworkEntry e = new NetworkEntry(0, id, null, tabOf(r), r.method(), r.url(), r.resourceType(), NetworkState.COMPLETED,
                status, statusText, mime, start == null ? Instant.now() : start, duration, null, from == null ? null : from.url(),
                false, false, r.headers(), respHeaders, null, body);
        safeListener(() -> listener.onNetwork(e));
    }

    private void onRequestFailed(Request r) {
        String id = requestId(r);
        responses.remove(id);
        Instant start = requestStarts.remove(id);
        String failure = r.failure();
        boolean blocked = failure != null && failure.contains("ERR_BLOCKED_BY_CLIENT");
        NetworkEntry e = new NetworkEntry(0, id, null, tabOf(r), r.method(), r.url(), r.resourceType(), NetworkState.FAILED,
                null, null, null, start == null ? Instant.now() : start, duration(r, start),
                blocked ? "Blocked by URL policy" : failure, null, false, false, r.headers(), null, null, null);
        safeListener(() -> listener.onNetwork(e));
    }

    private void onWebSocket(String tabId, WebSocket ws) {
        String id = "ws-" + requestSeq.incrementAndGet();
        Instant start = Instant.now();
        NetworkEntry open = new NetworkEntry(0, id, null, tabId, "GET", ws.url(), "websocket", NetworkState.PENDING, 101,
                "Switching Protocols", null, start, null, null, null, false, true, Map.of(), Map.of(), null, null);
        safeListener(() -> listener.onNetwork(open));
        ws.onClose(w -> safeListener(() -> listener.onNetwork(new NetworkEntry(0, id, null, tabId, "GET", ws.url(), "websocket",
                NetworkState.COMPLETED, 101, "Closed", null, start, (double) Duration.between(start, Instant.now()).toMillis(),
                null, null, false, true, Map.of(), Map.of(), null, null))));
        ws.onSocketError(err -> safeListener(() -> listener.onNetwork(new NetworkEntry(0, id, null, tabId, "GET", ws.url(),
                "websocket", NetworkState.FAILED, null, null, null, start, null, err, null, false, true, Map.of(), Map.of(), null, null))));
    }

    private static Double duration(Request r, Instant start) {
        try {
            Timing t = r.timing();
            if (t != null && t.responseEnd > 0) return Math.round(t.responseEnd * 10) / 10.0;
        } catch (RuntimeException ignored) {
            // timing unavailable
        }
        return start == null ? null : (double) Duration.between(start, Instant.now()).toMillis();
    }

    private static boolean isTextual(String mime) {
        if (mime == null) return false;
        String m = mime.toLowerCase(Locale.ROOT);
        return m.startsWith("text/") || m.contains("json") || m.contains("xml") || m.contains("javascript")
                || m.contains("x-www-form-urlencoded") || m.contains("graphql");
    }

    private void onConsole(String tabId, String type, String text, String location) {
        SourceLocation src = null;
        if (location != null && !location.isBlank()) {
            Matcher m = LOCATION.matcher(location);
            if (m.matches() && !m.group(1).isEmpty()) {
                // Playwright reports 0-based line/column; protocol locations are 1-based.
                src = SourceLocation.ofUrl(m.group(1), Integer.parseInt(m.group(2)) + 1, Integer.parseInt(m.group(3)) + 1);
            }
        }
        emitConsole(tabId, ConsoleLevel.fromConsoleType(type), ConsoleKind.CONSOLE, text, src, List.of());
    }

    /** Playwright reports "message, newline, stack"; the stack repeats the message line. Keep the first line. */
    static String errorMessage(String err) {
        String m = StackTraceParser.message(err);
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl).trim() : m;
    }

    private void onRejection(String message, String stack) {
        // A matching page error may already be pending: drop it, the rejection entry is more specific.
        pendingErrors.removeIf(p -> p.message().equals(message) || p.message().endsWith(message));
        String tab = activeTabId;
        List<StackFrame> frames = StackTraceParser.parse(stack);
        emitConsole(tab, ConsoleLevel.ERROR, ConsoleKind.UNHANDLED_REJECTION, "Unhandled promise rejection: " + message,
                frames.isEmpty() ? null : frames.get(0).location(), frames);
        recentRejections.put(message, Instant.now());
        trim(recentRejections);
    }

    private final Map<String, Instant> recentRejections = new LinkedHashMap<>();

    /** Called by the worker on every idle tick: flushes page errors that were not rejections. */
    void tick() {
        restoreBlockedTabs();
        if (pendingErrors.isEmpty()) return;
        Instant cutoff = Instant.now().minusMillis(150);
        for (Iterator<PendingError> it = pendingErrors.iterator(); it.hasNext(); ) {
            PendingError p = it.next();
            if (p.at().isAfter(cutoff)) continue;
            it.remove();
            Instant rej = recentRejections.get(p.message());
            if (rej != null && Duration.between(rej, Instant.now()).toSeconds() < 2) continue;
            List<StackFrame> frames = StackTraceParser.parse(p.stack());
            emitConsole(p.tabId(), ConsoleLevel.ERROR, ConsoleKind.PAGE_ERROR, p.message(),
                    frames.isEmpty() ? null : frames.get(0).location(), frames);
        }
    }

    /** A blocked navigation leaves Chrome's error page; go back to the page the user was on. */
    private void restoreBlockedTabs() {
        if (blockedTabs.isEmpty()) return;
        List<String> ids = new ArrayList<>(blockedTabs);
        blockedTabs.clear();
        for (String id : ids) {
            Page p = tabs.get(id);
            if (p == null || p.isClosed()) continue;
            try {
                if (p.url().startsWith("chrome-error://")) {
                    p.goBack(new Page.GoBackOptions().setTimeout(5000));
                }
            } catch (RuntimeException e) {
                log.debug("Could not restore tab {} after blocked navigation: {}", id, e.toString());
            }
        }
    }

    private void emitConsole(String tabId, ConsoleLevel level, ConsoleKind kind, String text, SourceLocation src,
                             List<StackFrame> frames) {
        String msg = text == null ? "" : (text.length() > 10_000 ? text.substring(0, 10_000) + "...[truncated]" : text);
        ConsoleEntry e = new ConsoleEntry(0, UUID.randomUUID().toString(), null, tabId, Instant.now(), level, kind, msg, src, null, frames);
        safeListener(() -> listener.onConsole(e));
    }

    private void safeListener(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("Event listener failed: {}", e.toString());
        }
    }

    private static <K, V> void trim(Map<K, V> map) {
        while (map.size() > 5000) {
            Iterator<K> it = map.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    // ------------------------------------------------------------------ helpers

    Page anyOpenPage() {
        for (Page p : tabs.values()) {
            if (!p.isClosed()) return p;
        }
        return null;
    }

    void browserDisconnected() {
        if (closed || !connected) return;
        connected = false;
        safeListener(() -> listener.onDisconnected("Browser process disconnected"));
    }

    private <T> T onWorker(String what, Duration timeout, java.util.concurrent.Callable<T> task) {
        if (closed) throw new BridgeException(ErrorCode.SESSION_NOT_RUNNING, "Session is closed");
        return worker.call(() -> {
            try {
                T result = task.call();
                tick();
                return result;
            } catch (BridgeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw PlaywrightErrors.translate(e, what);
            }
        }, timeout);
    }

    private Duration op() {
        return spec.operationTimeout().plusSeconds(5);
    }

    private Page page(String tabId) {
        String id = tabId == null ? activeTabId : tabId;
        Page p = id == null ? null : tabs.get(id);
        if (p == null || p.isClosed()) {
            if (tabId != null) throw new BridgeException(ErrorCode.NOT_FOUND, "Tab not found: " + tabId);
            if (!connected) throw new BridgeException(ErrorCode.SESSION_NOT_RUNNING, "Browser disconnected");
            Page np = context.newPage();
            registerTab(np);
            return np;
        }
        return p;
    }

    private String idOf(Page p) {
        return tabIds.get(p);
    }

    private TabInfo tabInfo(String id, Page p) {
        String title;
        try {
            title = p.title();
        } catch (RuntimeException e) {
            title = "";
        }
        return new TabInfo(id, p.url(), title, id.equals(activeTabId));
    }

    private NavigationResult result(Page p, Response r, long t0) {
        return new NavigationResult(idOf(p), p.url(), safeTitle(p), r == null ? null : r.status(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    private static String safeTitle(Page p) {
        try {
            return p.title();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static WaitUntilState waitUntil(WaitUntil w) {
        if (w == null) return WaitUntilState.LOAD;
        return switch (w) {
            case COMMIT -> WaitUntilState.COMMIT;
            case DOMCONTENTLOADED -> WaitUntilState.DOMCONTENTLOADED;
            case LOAD -> WaitUntilState.LOAD;
            case NETWORKIDLE -> WaitUntilState.NETWORKIDLE;
        };
    }

    private static LoadState loadState(WaitUntil w) {
        return switch (w) {
            case COMMIT, DOMCONTENTLOADED -> LoadState.DOMCONTENTLOADED;
            case LOAD -> LoadState.LOAD;
            case NETWORKIDLE -> LoadState.NETWORKIDLE;
        };
    }

    // ------------------------------------------------------------------ BrowserSessionDriver

    @Override
    public String contextId() {
        return contextId;
    }

    @Override
    public ProviderInfo providerInfo() {
        return new ProviderInfo(PlaywrightProvider.ID, spec.connectEndpoint() != null ? "Playwright CDP attach" : "Playwright Chromium",
                PlaywrightProvider.playwrightVersion(), "chromium", browserVersion);
    }

    @Override
    public boolean isConnected() {
        return connected && !closed;
    }

    @Override
    public List<TabInfo> tabs() {
        return onWorker("list tabs", op(), () -> {
            List<TabInfo> out = new ArrayList<>();
            tabs.forEach((id, p) -> out.add(tabInfo(id, p)));
            return out;
        });
    }

    @Override
    public String activeTabId() {
        return activeTabId;
    }

    @Override
    public TabInfo openTab(String url) {
        return onWorker("open tab", spec.navigationTimeout().plusSeconds(5), () -> {
            Page p = context.newPage();
            registerTab(p);
            String id = idOf(p);
            activeTabId = id;
            if (url != null) p.navigate(url);
            return tabInfo(id, p);
        });
    }

    @Override
    public void closeTab(String tabId) {
        onWorker("close tab", op(), () -> {
            Page p = tabs.get(tabId);
            if (p == null) throw new BridgeException(ErrorCode.NOT_FOUND, "Tab not found: " + tabId);
            p.close();
            return null;
        });
    }

    @Override
    public TabInfo activateTab(String tabId) {
        return onWorker("activate tab", op(), () -> {
            Page p = tabs.get(tabId);
            if (p == null) throw new BridgeException(ErrorCode.NOT_FOUND, "Tab not found: " + tabId);
            p.bringToFront();
            activeTabId = tabId;
            return tabInfo(tabId, p);
        });
    }

    @Override
    public NavigationResult navigate(String tabId, String url, WaitUntil waitUntil, Duration timeout) {
        return onWorker("navigate", timeout.plusSeconds(5), () -> {
            Page p = page(tabId);
            String id = idOf(p);
            long t0 = System.nanoTime();
            safeListener(() -> listener.onNavigation(id, url, NavigationPhase.STARTED, null));
            try {
                Response r = p.navigate(url, new Page.NavigateOptions().setWaitUntil(waitUntil(waitUntil)).setTimeout(timeout.toMillis()));
                return result(p, r, t0);
            } catch (RuntimeException e) {
                BridgeException be = PlaywrightErrors.translate(e, "Navigation to " + url + " failed");
                safeListener(() -> listener.onNavigation(id, url, NavigationPhase.FAILED, be.getMessage()));
                throw be;
            }
        });
    }

    @Override
    public NavigationResult reload(String tabId, Duration timeout) {
        return onWorker("reload", timeout.plusSeconds(5), () -> {
            Page p = page(tabId);
            long t0 = System.nanoTime();
            return result(p, p.reload(new Page.ReloadOptions().setTimeout(timeout.toMillis())), t0);
        });
    }

    @Override
    public NavigationResult back(String tabId, Duration timeout) {
        return onWorker("go back", timeout.plusSeconds(5), () -> {
            Page p = page(tabId);
            long t0 = System.nanoTime();
            return result(p, p.goBack(new Page.GoBackOptions().setTimeout(timeout.toMillis())), t0);
        });
    }

    @Override
    public NavigationResult forward(String tabId, Duration timeout) {
        return onWorker("go forward", timeout.plusSeconds(5), () -> {
            Page p = page(tabId);
            long t0 = System.nanoTime();
            return result(p, p.goForward(new Page.GoForwardOptions().setTimeout(timeout.toMillis())), t0);
        });
    }

    @Override
    public PageInfo stop(String tabId) {
        return onWorker("stop", op(), () -> {
            Page p = page(tabId);
            // Same as the browser's stop button: abort pending loads of the main frame.
            p.evaluate("() => window.stop()");
            return pageInfoOn(p);
        });
    }

    @Override
    public PageInfo pageInfo(String tabId) {
        return onWorker("page info", op(), () -> pageInfoOn(page(tabId)));
    }

    private PageInfo pageInfoOn(Page p) {
        String ready;
        try {
            ready = String.valueOf(p.evaluate(Scripts.READY_STATE));
        } catch (RuntimeException e) {
            ready = "unknown";
        }
        return new PageInfo(idOf(p), p.url(), safeTitle(p), ready);
    }

    @Override
    public WaitResult waitFor(WaitRequest request, Duration timeout) {
        return onWorker("wait", timeout.plusSeconds(5), () -> {
            Page p = page(request.tabId());
            long t0 = System.nanoTime();
            double ms = timeout.toMillis();
            try {
                if (request.locator() != null && !request.locator().isEmpty()) {
                    ElementState st = request.state() == null ? ElementState.VISIBLE : request.state();
                    Locators.resolve(p, request.locator()).first().waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                            .setState(WaitForSelectorState.valueOf(st.name())).setTimeout(ms));
                }
                if (request.text() != null) {
                    p.getByText(request.text()).first().waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE).setTimeout(ms));
                }
                if (request.urlPattern() != null) {
                    p.waitForURL(request.urlPattern(), new Page.WaitForURLOptions().setTimeout(ms).setWaitUntil(WaitUntilState.COMMIT));
                }
                if (request.loadState() != null) {
                    p.waitForLoadState(loadState(request.loadState()), new Page.WaitForLoadStateOptions().setTimeout(ms));
                }
                return new WaitResult(true, (System.nanoTime() - t0) / 1_000_000, null);
            } catch (TimeoutError e) {
                return new WaitResult(false, (System.nanoTime() - t0) / 1_000_000, "Timed out after " + timeout.toMillis()
                        + " ms waiting for " + describe(request));
            }
        });
    }

    private static String describe(WaitRequest r) {
        List<String> parts = new ArrayList<>();
        if (r.locator() != null && !r.locator().isEmpty()) {
            parts.add(r.locator().describe() + " to be " + (r.state() == null ? "VISIBLE" : r.state()));
        }
        if (r.text() != null) parts.add("text \"" + r.text() + "\"");
        if (r.urlPattern() != null) parts.add("URL " + r.urlPattern());
        if (r.loadState() != null) parts.add("load state " + r.loadState());
        return String.join(", ", parts);
    }

    @Override
    public ActionResult perform(ActionRequest r, Duration timeout) {
        return onWorker("action " + r.type(), timeout.plusSeconds(5), () -> {
            Page p = page(r.tabId());
            String before = p.url();
            long t0 = System.nanoTime();
            double ms = timeout.toMillis();
            com.microsoft.playwright.Locator loc = r.locator() != null && !r.locator().isEmpty() ? Locators.resolve(p, r.locator()) : null;
            try {
                Object value = execute(p, r, loc, ms);
                String after = p.url();
                return ActionResult.ok(r.type(), (System.nanoTime() - t0) / 1_000_000, value, after, !after.equals(before));
            } catch (TimeoutError e) {
                long dur = (System.nanoTime() - t0) / 1_000_000;
                boolean missing = loc != null && safeCount(loc) == 0;
                return ActionResult.failed(r.type(), dur, missing ? ErrorCode.ELEMENT_NOT_FOUND : ErrorCode.TIMEOUT,
                        missing ? "No element matches " + r.locator().describe()
                                : "Element " + (r.locator() == null ? "" : r.locator().describe()) + " was not actionable within "
                                + timeout.toMillis() + " ms: " + PlaywrightErrors.firstLine(e), p.url());
            } catch (RuntimeException e) {
                BridgeException be = PlaywrightErrors.translate(e, r.type().name());
                if (be.code() == ErrorCode.SESSION_NOT_RUNNING) throw be;
                return ActionResult.failed(r.type(), (System.nanoTime() - t0) / 1_000_000, be.code(), be.getMessage(), p.url());
            }
        });
    }

    private static int safeCount(com.microsoft.playwright.Locator loc) {
        try {
            return loc.count();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private Object execute(Page p, ActionRequest r, com.microsoft.playwright.Locator loc, double ms) {
        switch (r.type()) {
            case CLICK -> loc.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(ms));
            case DOUBLE_CLICK -> loc.dblclick(new com.microsoft.playwright.Locator.DblclickOptions().setTimeout(ms));
            case HOVER -> loc.hover(new com.microsoft.playwright.Locator.HoverOptions().setTimeout(ms));
            case FOCUS -> loc.focus(new com.microsoft.playwright.Locator.FocusOptions().setTimeout(ms));
            case FILL -> loc.fill(r.value(), new com.microsoft.playwright.Locator.FillOptions().setTimeout(ms));
            case CLEAR -> loc.clear(new com.microsoft.playwright.Locator.ClearOptions().setTimeout(ms));
            case TYPE -> loc.pressSequentially(r.value(), new com.microsoft.playwright.Locator.PressSequentiallyOptions().setTimeout(ms));
            case PRESS -> {
                if (loc != null) loc.press(r.key(), new com.microsoft.playwright.Locator.PressOptions().setTimeout(ms));
                else p.keyboard().press(r.key());
            }
            case SELECT -> {
                List<String> values = r.values() != null && !r.values().isEmpty() ? r.values() : List.of(r.value());
                List<String> selected = selectByValueOrLabel(loc, values, ms);
                return selected;
            }
            case CHECK -> loc.check(new com.microsoft.playwright.Locator.CheckOptions().setTimeout(ms));
            case UNCHECK -> loc.uncheck(new com.microsoft.playwright.Locator.UncheckOptions().setTimeout(ms));
            case SCROLL -> {
                int dx = r.deltaX() == null ? 0 : r.deltaX();
                int dy = r.deltaY() == null ? 0 : r.deltaY();
                if (loc != null) {
                    loc.scrollIntoViewIfNeeded(new com.microsoft.playwright.Locator.ScrollIntoViewIfNeededOptions().setTimeout(ms));
                    if (dx != 0 || dy != 0) loc.evaluate("(el, d) => el.scrollBy(d.dx, d.dy)", Map.of("dx", dx, "dy", dy));
                } else {
                    p.mouse().wheel(dx, dy == 0 && dx == 0 ? 500 : dy);
                }
                return p.evaluate("() => ({x: Math.round(window.scrollX), y: Math.round(window.scrollY)})");
            }
            case DRAG_AND_DROP -> loc.dragTo(Locators.resolve(p, r.target()), new com.microsoft.playwright.Locator.DragToOptions().setTimeout(ms));
            case GET_TEXT -> {
                return loc.innerText(new com.microsoft.playwright.Locator.InnerTextOptions().setTimeout(ms));
            }
            case GET_ATTRIBUTE -> {
                String attr = r.attribute();
                if (attr.equalsIgnoreCase("value")) {
                    Object sensitive = loc.evaluate("(el) => el.matches('" + SENSITIVE_MASK_SELECTOR.replace("'", "\\'")
                            + "') || /pass|secret|token|otp|cvv|cvc|ssn/i.test((el.name||'') + (el.id||''))",
                            null, new com.microsoft.playwright.Locator.EvaluateOptions().setTimeout(ms));
                    if (Boolean.TRUE.equals(sensitive)) return "[REDACTED]";
                    return loc.inputValue(new com.microsoft.playwright.Locator.InputValueOptions().setTimeout(ms));
                }
                return loc.getAttribute(attr, new com.microsoft.playwright.Locator.GetAttributeOptions().setTimeout(ms));
            }
            case IS_VISIBLE -> {
                return loc.isVisible();
            }
            case CLICK_AT -> p.mouse().click(r.x(), r.y(), new com.microsoft.playwright.Mouse.ClickOptions().setButton(MouseButton.LEFT));
        }
        return null;
    }

    private static List<String> selectByValueOrLabel(com.microsoft.playwright.Locator loc, List<String> values, double ms) {
        try {
            return loc.selectOption(values.toArray(new String[0]), new com.microsoft.playwright.Locator.SelectOptionOptions().setTimeout(ms));
        } catch (RuntimeException e) {
            com.microsoft.playwright.options.SelectOption[] labels = values.stream()
                    .map(v -> new com.microsoft.playwright.options.SelectOption().setLabel(v))
                    .toArray(com.microsoft.playwright.options.SelectOption[]::new);
            return loc.selectOption(labels, new com.microsoft.playwright.Locator.SelectOptionOptions().setTimeout(ms));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public DomSnapshot snapshot(SnapshotRequest request) {
        return onWorker("snapshot", op(), () -> {
            Page p = page(request.tabId());
            String tab = idOf(p);
            if (request.mode() == SnapshotMode.ACCESSIBILITY) {
                String aria = request.root() != null ? p.locator(request.root()).first().ariaSnapshot() : p.locator("body").ariaSnapshot();
                return new DomSnapshot(safeTitle(p), p.url(), tab, SnapshotMode.ACCESSIBILITY, 0, false, List.of(), aria);
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("mode", request.mode().name());
            args.put("root", request.root());
            args.put("maxElements", request.maxElements());
            args.put("includeHidden", request.includeHidden());
            args.put("role", request.role());
            args.put("text", request.text());
            Map<String, Object> res = (Map<String, Object>) p.evaluate(Scripts.SNAPSHOT, args);
            List<Map<String, Object>> raw = (List<Map<String, Object>>) res.get("elements");
            List<DomElement> elements = new ArrayList<>(raw.size());
            for (Map<String, Object> m : raw) elements.add(toElement(m));
            return new DomSnapshot(str(res.get("title")), str(res.get("url")), tab, request.mode(), elements.size(),
                    Boolean.TRUE.equals(res.get("truncated")), elements, null);
        });
    }

    @SuppressWarnings("unchecked")
    private static DomElement toElement(Map<String, Object> m) {
        return new DomElement(str(m.get("reference")), str(m.get("parent")), num(m.get("depth")), str(m.get("tag")),
                str(m.get("role")), str(m.get("name")), str(m.get("text")), Boolean.TRUE.equals(m.get("visible")),
                Boolean.TRUE.equals(m.get("interactive")), Boolean.TRUE.equals(m.get("disabled")),
                m.get("checked") instanceof Boolean b ? b : null, str(m.get("value")),
                strMap((Map<String, Object>) m.get("attributes")), bounds((Map<String, Object>) m.get("bounds")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public ElementDetails inspect(InspectRequest request) {
        return onWorker("inspect", op(), () -> {
            Page p = page(request.tabId());
            com.microsoft.playwright.Locator loc = Locators.resolve(p, request.locator()).first();
            if (loc.count() == 0) throw new BridgeException(ErrorCode.ELEMENT_NOT_FOUND, "No element matches " + request.locator().describe());
            Map<String, Object> m = (Map<String, Object>) loc.evaluate(Scripts.INSPECT,
                    request.styles() == null ? List.of() : request.styles());
            Map<String, Object> ff = (Map<String, Object>) m.get("formField");
            FormFieldInfo form = ff == null ? null : new FormFieldInfo(str(ff.get("type")), str(ff.get("name")), str(ff.get("label")),
                    Boolean.TRUE.equals(ff.get("required")), str(ff.get("placeholder")), str(ff.get("value")),
                    Boolean.TRUE.equals(ff.get("sensitive")), !Boolean.FALSE.equals(ff.get("valid")), str(ff.get("validationMessage")));
            Map<String, String> attrs = strMap((Map<String, Object>) m.get("attributes"));
            if (form != null && form.sensitive() && attrs != null) attrs.remove("value");
            return new ElementDetails(str(m.get("reference")), str(m.get("tag")), str(m.get("role")), str(m.get("name")),
                    form != null && form.sensitive() ? null : str(m.get("text")), Boolean.TRUE.equals(m.get("visible")),
                    Boolean.TRUE.equals(m.get("enabled")), Boolean.TRUE.equals(m.get("editable")),
                    m.get("checked") instanceof Boolean b ? b : null, attrs, strMap((Map<String, Object>) m.get("computedStyles")),
                    bounds((Map<String, Object>) m.get("bounds")), form, (List<String>) m.get("ancestors"), num(m.get("childCount")));
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public AccessibilityReport accessibility(String tabId, String root) {
        return onWorker("accessibility", op(), () -> {
            Page p = page(tabId);
            String aria = (root != null ? p.locator(root).first() : p.locator("body")).ariaSnapshot();
            List<Map<String, Object>> raw = (List<Map<String, Object>>) p.evaluate(Scripts.AUDIT);
            List<AccessibilityIssue> issues = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                issues.add(new AccessibilityIssue(str(m.get("rule")), str(m.get("severity")), str(m.get("message")), str(m.get("reference"))));
            }
            return new AccessibilityReport(idOf(p), p.url(), aria, issues);
        });
    }

    @Override
    public CapturedImage screenshot(ScreenshotRequest r) {
        return onWorker("screenshot", spec.navigationTimeout().plusSeconds(5), () -> {
            Page p = page(r.tabId());
            ImageFormat format = r.format() == null ? ImageFormat.PNG : r.format();
            ScreenshotType type = format == ImageFormat.JPEG ? ScreenshotType.JPEG : ScreenshotType.PNG;
            List<com.microsoft.playwright.Locator> mask = r.maskSensitive() == null || r.maskSensitive()
                    ? List.of(p.locator(SENSITIVE_MASK_SELECTOR)) : List.of();
            byte[] bytes;
            if (r.locator() != null && !r.locator().isEmpty()) {
                com.microsoft.playwright.Locator.ScreenshotOptions o = new com.microsoft.playwright.Locator.ScreenshotOptions()
                        .setType(type).setMask(mask);
                if (type == ScreenshotType.JPEG && r.quality() != null) o.setQuality(r.quality());
                bytes = Locators.resolve(p, r.locator()).first().screenshot(o);
            } else {
                Page.ScreenshotOptions o = new Page.ScreenshotOptions().setType(type).setFullPage(Boolean.TRUE.equals(r.fullPage()))
                        .setMask(mask);
                if (type == ScreenshotType.JPEG && r.quality() != null) o.setQuality(r.quality());
                bytes = p.screenshot(o);
            }
            return new CapturedImage(bytes, format, p.url());
        });
    }

    @Override
    public void setViewport(String tabId, Viewport viewport) {
        onWorker("set viewport", op(), () -> {
            page(tabId).setViewportSize(viewport.width(), viewport.height());
            return null;
        });
    }

    @Override
    public Object evaluate(String tabId, String expression) {
        return onWorker("evaluate", op(), () -> page(tabId).evaluate(expression));
    }

    @Override
    public void restart() {
        onWorker("restart", Duration.ofSeconds(120), () -> {
            List<String> urls = new ArrayList<>();
            for (Page p : tabs.values()) {
                try {
                    if (!p.isClosed()) urls.add(p.url());
                } catch (RuntimeException ignored) {
                    // dead page
                }
            }
            if (urls.isEmpty()) urls = lastUrls;
            try {
                if (context != null) context.close();
            } catch (RuntimeException ignored) {
                // context already gone with the browser
            }
            if (dedicated && browser != null) {
                try {
                    browser.close();
                } catch (RuntimeException ignored) {
                    // already disconnected
                }
            }
            tabs.clear();
            tabIds.clear();
            activeTabId = null;
            connected = true;
            createContext();
            Page first = page(null);
            boolean firstUsed = false;
            for (String u : urls) {
                if (u == null || u.equals("about:blank") || !spec.guard().isAllowed(u)) continue;
                Page target = firstUsed ? context.newPage() : first;
                firstUsed = true;
                try {
                    target.navigate(u);
                } catch (RuntimeException e) {
                    log.debug("Could not reopen {}: {}", u, e.toString());
                }
            }
            lastUrls = urls;
            return null;
        });
    }

    @Override
    public void close() {
        if (closed) return;
        try {
            worker.call(() -> {
                closed = true;
                worker.unregister(this);
                try {
                    if (context != null) context.close();
                } catch (RuntimeException e) {
                    log.debug("Context close failed: {}", e.toString());
                }
                if (dedicated && browser != null) {
                    try {
                        browser.close();
                    } catch (RuntimeException e) {
                        log.debug("Browser close failed: {}", e.toString());
                    }
                }
                return null;
            }, Duration.ofSeconds(30));
        } catch (RuntimeException e) {
            log.warn("Session close failed: {}", e.toString());
        } finally {
            closed = true;
            if (dedicated) worker.close();
        }
    }

    // ------------------------------------------------------------------ conversion helpers

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static Map<String, String> strMap(Map<String, Object> m) {
        if (m == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }

    private static Bounds bounds(Map<String, Object> m) {
        if (m == null) return null;
        return new Bounds(dbl(m.get("x")), dbl(m.get("y")), dbl(m.get("width")), dbl(m.get("height")));
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
