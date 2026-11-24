package dev.cdb.provider.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cdb.core.BridgeException;
import dev.cdb.core.network.NetworkLog;
import dev.cdb.core.page.PageResults;
import dev.cdb.core.page.PageScript;
import dev.cdb.core.security.UrlPolicy;
import dev.cdb.core.spi.BrowserEventListener;
import dev.cdb.core.spi.BrowserEventListener.NavigationPhase;
import dev.cdb.core.spi.BrowserSessionDriver;
import dev.cdb.core.spi.CapturedImage;
import dev.cdb.core.spi.SessionLaunchSpec;
import dev.cdb.protocol.*;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * One tab in the user's own Chrome, controlled with raw CDP relayed by the extension (chrome.debugger).
 * Reads and actions are only allowed while the page is on an allowed origin (development host or approved).
 */
final class ExtensionSessionDriver implements BrowserSessionDriver {
    private static final Duration POLL = Duration.ofMillis(100);
    private static final String MASK_STYLE_ID = "__cdb_mask_style";
    private static final String MASK_CSS = "input[type=password], input[autocomplete*=cc-], input[autocomplete=one-time-code], "
            + "[data-cdb-sensitive] { background: #9E9E9E !important; color: transparent !important; "
            + "-webkit-text-fill-color: transparent !important; text-shadow: none !important; }";

    private final ExtensionConnection conn;
    private final int chromeTabId;
    private final String tabId;
    private final SessionLaunchSpec spec;
    private final BrowserEventListener listener;
    private final CdpEventTranslator translator;
    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong navStarted = new AtomicLong();
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong loads = new AtomicLong();
    private final AtomicLong domReady = new AtomicLong();
    private final Object monitor = new Object();
    private volatile String mainFrameId;
    private volatile String url = "about:blank";
    private volatile String title = "";
    private volatile boolean connected = true;
    private volatile boolean closed;
    private volatile String sessionId;

    ExtensionSessionDriver(ExtensionConnection conn, int chromeTabId, SessionLaunchSpec spec, BrowserEventListener listener) {
        this.conn = conn;
        this.chromeTabId = chromeTabId;
        this.tabId = "tab-" + chromeTabId;
        this.spec = spec;
        this.listener = listener;
        this.translator = new CdpEventTranslator(tabId, listener);
        this.mapper = conn.mapper();
    }

    void init() {
        cdp("Page.enable", null);
        cdp("Runtime.enable", null);
        cdp("Network.enable", null);
        cdp("Log.enable", null);
        JsonNode tree = cdp("Page.getFrameTree", null);
        mainFrameId = tree.path("frameTree").path("frame").path("id").asText(null);
        url = tree.path("frameTree").path("frame").path("url").asText(url);
        try {
            title = eval(PageScript.call("pageState")).path("title").asText("");
        } catch (RuntimeException ignored) {
            // page not scriptable yet
        }
        connected = true;
    }

    String sessionId() {
        return sessionId;
    }

    void sessionId(String id) {
        this.sessionId = id;
    }

    // ---------------------------------------------------------------- events (WebSocket thread; never block)

    void onEvent(String method, JsonNode p) {
        switch (method) {
            case "Page.frameNavigated" -> {
                JsonNode f = p.path("frame");
                if (!f.has("parentId")) {
                    mainFrameId = f.path("id").asText(mainFrameId);
                    url = f.path("url").asText(url);
                    bump(committed);
                }
            }
            case "Page.navigatedWithinDocument" -> {
                if (p.path("frameId").asText("").equals(mainFrameId)) url = p.path("url").asText(url);
            }
            case "Page.frameStartedLoading" -> {
                if (p.path("frameId").asText("").equals(mainFrameId)) bump(navStarted);
            }
            case "Page.domContentEventFired" -> bump(domReady);
            case "Page.loadEventFired" -> {
                bump(loads);
                if (allowed()) safe(() -> listener.onNavigation(tabId, url, NavigationPhase.COMPLETED, null));
            }
            case "Inspector.detached", "Inspector.targetCrashed" -> detachedByBrowser(method);
            default -> {
                // Console and network data are only captured while the page is on an allowed origin.
                if (allowed()) translator.handle(method, p, spec.captureBodies());
            }
        }
    }

    void tabUpdated(String newUrl, String newTitle) {
        if (newUrl != null) url = newUrl;
        if (newTitle != null) title = newTitle;
    }

    void detachedByBrowser(String reason) {
        if (closed || !connected) return;
        connected = false;
        safe(() -> listener.onDisconnected("Chrome tab no longer shared (" + reason + ")"));
    }

    private void bump(AtomicLong counter) {
        counter.incrementAndGet();
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // listeners must not break the event pump
        }
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode cdp(String method, JsonNode params) {
        return cdp(method, params, spec.operationTimeout().plusSeconds(5));
    }

    private JsonNode cdp(String method, JsonNode params, Duration timeout) {
        if (closed) throw new BridgeException(ErrorCode.SESSION_NOT_RUNNING, "Session is closed");
        if (!connected) throw new BridgeException(ErrorCode.SESSION_NOT_RUNNING, "Chrome tab is no longer shared");
        return conn.cdp(chromeTabId, method, params, timeout);
    }

    private ObjectNode obj() {
        return mapper.createObjectNode();
    }

    /** Evaluates an expression in the page's main world and returns its JSON value. */
    private JsonNode eval(String expression) {
        ObjectNode p = obj().put("expression", expression).put("returnByValue", true).put("awaitPromise", true)
                .put("userGesture", true);
        JsonNode r = cdp("Runtime.evaluate", p);
        if (r.has("exceptionDetails")) {
            String d = r.path("exceptionDetails").path("exception").path("description")
                    .asText(r.path("exceptionDetails").path("text").asText("Script error"));
            int nl = d.indexOf('\n');
            throw new PageScriptException(nl > 0 ? d.substring(0, nl) : d);
        }
        return r.path("result").path("value");
    }

    static final class PageScriptException extends BridgeException {
        PageScriptException(String message) {
            super(ErrorCode.BROWSER_ERROR, message.replaceFirst("^Error: ", ""));
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private boolean allowed() {
        return spec.guard().isAllowed(url);
    }

    private void requireAllowedPage() {
        if (!allowed()) {
            throw new BridgeException(ErrorCode.URL_NOT_ALLOWED, "The shared tab is on " + originOrUrl(url)
                    + ", which is not a development host or an approved origin. Approve it in the IDE (Sessions tab) or "
                    + "in the extension popup to let tools read this page.");
        }
    }

    static String originOrUrl(String u) {
        try {
            URI uri = URI.create(u);
            return uri.getHost() == null ? u : UrlPolicy.origin(uri);
        } catch (IllegalArgumentException e) {
            return "an unknown page";
        }
    }

    private void requireOwnTab(String requested) {
        if (requested != null && !requested.equals(tabId)) {
            throw new BridgeException(ErrorCode.NOT_FOUND, "Tab not found: " + requested + " (shared Chrome sessions have one tab)");
        }
    }

    private <T> T locked(java.util.function.Supplier<T> s) {
        lock.lock();
        try {
            return s.get();
        } finally {
            lock.unlock();
        }
    }

    private boolean await(AtomicLong counter, long after, long deadlineNanos) {
        synchronized (monitor) {
            while (counter.get() <= after) {
                long left = deadlineNanos - System.nanoTime();
                if (left <= 0) return false;
                try {
                    monitor.wait(Math.max(1, left / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (!connected) return false;
            }
            return true;
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void refreshTitle() {
        try {
            title = eval(PageScript.call("pageState")).path("title").asText(title);
        } catch (RuntimeException ignored) {
            // navigating
        }
    }

    // ---------------------------------------------------------------- BrowserSessionDriver: identity & tabs

    @Override
    public String contextId() {
        return "chrome-tab-" + chromeTabId;
    }

    @Override
    public ProviderInfo providerInfo() {
        return conn.providerInfo();
    }

    @Override
    public boolean isConnected() {
        return connected && !closed;
    }

    @Override
    public List<TabInfo> tabs() {
        boolean ok = allowed();
        return List.of(new TabInfo(tabId, ok ? url : originOrUrl(url), ok ? title : "", true));
    }

    @Override
    public String activeTabId() {
        return tabId;
    }

    @Override
    public TabInfo openTab(String newUrl) {
        throw new BridgeException(ErrorCode.UNSUPPORTED, "Shared Chrome sessions control a single tab; share another tab from the extension");
    }

    @Override
    public void closeTab(String id) {
        throw new BridgeException(ErrorCode.UNSUPPORTED, "The shared tab belongs to your browser; close it in Chrome or stop sharing");
    }

    @Override
    public TabInfo activateTab(String id) {
        requireOwnTab(id);
        conn.call("tab.activate", obj().put("tabId", chromeTabId), spec.operationTimeout());
        return tabs().get(0);
    }

    // ---------------------------------------------------------------- navigation

    @Override
    public NavigationResult navigate(String id, String target, WaitUntil waitUntil, Duration timeout) {
        requireOwnTab(id);
        return locked(() -> {
            long t0 = System.nanoTime();
            long loads0 = loads.get();
            long dcl0 = domReady.get();
            long commit0 = committed.get();
            safe(() -> listener.onNavigation(tabId, target, NavigationPhase.STARTED, null));
            JsonNode r = cdp("Page.navigate", obj().put("url", target), timeout.plusSeconds(5));
            String error = r.path("errorText").asText("");
            if (!error.isEmpty()) {
                safe(() -> listener.onNavigation(tabId, target, NavigationPhase.FAILED, error));
                throw new BridgeException(ErrorCode.BROWSER_ERROR, "Navigation to " + target + " failed: " + error);
            }
            String loaderId = r.path("loaderId").asText(null);
            long deadline = System.nanoTime() + timeout.toNanos();
            if (loaderId != null) {
                boolean ok = switch (waitUntil == null ? WaitUntil.LOAD : waitUntil) {
                    case COMMIT -> await(committed, commit0, deadline);
                    case DOMCONTENTLOADED -> await(domReady, dcl0, deadline);
                    case LOAD -> await(loads, loads0, deadline);
                    case NETWORKIDLE -> await(loads, loads0, deadline) && networkIdle(deadline);
                };
                if (!ok) throw new BridgeException(ErrorCode.TIMEOUT, "Navigation to " + target + " timed out after "
                        + timeout.toMillis() + " ms");
                settle(waitUntil == WaitUntil.COMMIT ? null : waitUntil == WaitUntil.DOMCONTENTLOADED ? "interactive" : "complete", deadline);
            }
            refreshTitle();
            return new NavigationResult(tabId, url, title, translator.documentStatus(loaderId), (System.nanoTime() - t0) / 1_000_000);
        });
    }

    /**
     * Load events of the previous document can arrive late; confirm in the page itself that the new document is in
     * the expected ready state (bounded by the navigation deadline, at most 5 s extra polling).
     */
    private void settle(String readyState, long deadline) {
        if (readyState == null) return;
        long until = Math.min(deadline, System.nanoTime() + 5_000_000_000L);
        while (System.nanoTime() < until) {
            try {
                JsonNode st = eval(PageScript.call("pageState"));
                String rs = st.path("readyState").asText();
                if (!st.path("url").asText().equals("about:blank") && (rs.equals("complete") || rs.equals(readyState))) {
                    url = st.path("url").asText(url);
                    title = st.path("title").asText(title);
                    return;
                }
            } catch (PageScriptException ignored) {
                // context destroyed mid-navigation
            }
            sleep(POLL);
        }
    }

    private boolean networkIdle(long deadline) {
        long quietSince = -1;
        while (System.nanoTime() < deadline) {
            if (translator.inflight() == 0) {
                if (quietSince < 0) quietSince = System.nanoTime();
                if (System.nanoTime() - quietSince >= 500_000_000L) return true;
            } else {
                quietSince = -1;
            }
            sleep(POLL);
        }
        return false;
    }

    @Override
    public NavigationResult reload(String id, Duration timeout) {
        requireOwnTab(id);
        return locked(() -> {
            long t0 = System.nanoTime();
            long loads0 = loads.get();
            cdp("Page.reload", obj());
            if (!await(loads, loads0, System.nanoTime() + timeout.toNanos())) {
                throw new BridgeException(ErrorCode.TIMEOUT, "Reload timed out");
            }
            refreshTitle();
            return new NavigationResult(tabId, url, title, null, (System.nanoTime() - t0) / 1_000_000);
        });
    }

    @Override
    public NavigationResult back(String id, Duration timeout) {
        return history(id, -1, timeout);
    }

    @Override
    public NavigationResult forward(String id, Duration timeout) {
        return history(id, 1, timeout);
    }

    private NavigationResult history(String id, int delta, Duration timeout) {
        requireOwnTab(id);
        return locked(() -> {
            long t0 = System.nanoTime();
            JsonNode h = cdp("Page.getNavigationHistory", obj());
            int idx = h.path("currentIndex").asInt() + delta;
            JsonNode entries = h.path("entries");
            if (idx >= 0 && idx < entries.size()) {
                long commit0 = committed.get();
                cdp("Page.navigateToHistoryEntry", obj().put("entryId", entries.get(idx).path("id").asInt()));
                await(committed, commit0, System.nanoTime() + timeout.toNanos());
                sleep(Duration.ofMillis(150));
                refreshTitle();
            }
            boolean ok = allowed();
            return new NavigationResult(tabId, ok ? url : originOrUrl(url), ok ? title : "", null, (System.nanoTime() - t0) / 1_000_000);
        });
    }

    @Override
    public PageInfo stop(String id) {
        requireOwnTab(id);
        cdp("Page.stopLoading", obj());
        return pageInfo(id);
    }

    @Override
    public PageInfo pageInfo(String id) {
        requireOwnTab(id);
        if (!allowed()) return new PageInfo(tabId, originOrUrl(url), "", "unknown");
        JsonNode s = eval(PageScript.call("pageState"));
        title = s.path("title").asText(title);
        return new PageInfo(tabId, s.path("url").asText(url), title, s.path("readyState").asText("unknown"));
    }

    // ---------------------------------------------------------------- waiting

    @Override
    public WaitResult waitFor(WaitRequest r, Duration timeout) {
        requireOwnTab(r.tabId());
        long t0 = System.nanoTime();
        long deadline = t0 + timeout.toNanos();
        Pattern urlGlob = r.urlPattern() == null ? null : NetworkLog.globToRegex(r.urlPattern());
        while (true) {
            boolean ok = true;
            if (urlGlob != null) ok = urlGlob.matcher(url).matches();
            if (ok && (r.locator() != null && !r.locator().isEmpty() || r.text() != null || r.loadState() != null)) {
                requireAllowedPage();
                try {
                    if (r.locator() != null && !r.locator().isEmpty()) ok = elementState(r.locator(), r.state());
                    if (ok && r.text() != null) ok = elementState(Locator.text(r.text()), ElementState.VISIBLE);
                    if (ok && r.loadState() != null) ok = loadState(r.loadState());
                } catch (PageScriptException e) {
                    ok = false; // page is navigating
                }
            }
            if (ok) return new WaitResult(true, (System.nanoTime() - t0) / 1_000_000, null);
            if (System.nanoTime() >= deadline) {
                return new WaitResult(false, (System.nanoTime() - t0) / 1_000_000, "Timed out after " + timeout.toMillis() + " ms");
            }
            sleep(POLL);
        }
    }

    private boolean elementState(Locator loc, ElementState state) {
        JsonNode s = eval(PageScript.wrap("H => { const L = " + json(loc) + "; let els = H.candidates(L); "
                + "if (L.nth != null) els = els[L.nth] ? [els[L.nth]] : []; "
                + "const vis = e => { const r = e.getBoundingClientRect(); const cs = getComputedStyle(e); "
                + "return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none'; }; "
                + "return { count: els.length, visible: els.some(vis) }; }"));
        int count = s.path("count").asInt();
        boolean visible = s.path("visible").asBoolean();
        return switch (state == null ? ElementState.VISIBLE : state) {
            case VISIBLE -> visible;
            case HIDDEN -> !visible;
            case ATTACHED -> count > 0;
            case DETACHED -> count == 0;
        };
    }

    private boolean loadState(WaitUntil w) {
        String ready = eval("document.readyState").asText();
        return switch (w) {
            case COMMIT -> true;
            case DOMCONTENTLOADED -> !ready.equals("loading");
            case LOAD -> ready.equals("complete");
            case NETWORKIDLE -> ready.equals("complete") && translator.inflight() == 0;
        };
    }

    // ---------------------------------------------------------------- actions

    private static final class ActionFailure extends RuntimeException {
        final ErrorCode code;

        ActionFailure(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Waits until the locator resolves to exactly one visible (and optionally enabled/editable) element. */
    private JsonNode actionable(Locator loc, long deadline, boolean enabled, boolean editable) {
        JsonNode last = null;
        String why = "not found";
        String scriptError = null;
        while (true) {
            try {
                last = eval(PageScript.call("target", json(loc)));
                int count = last.path("count").asInt();
                if (count > 1 && loc.nth() == null) {
                    throw new ActionFailure(ErrorCode.VALIDATION_FAILED, loc.describe() + " matched " + count
                            + " elements; use a more specific locator or nth");
                }
                if (count == 1) {
                    if (!last.path("visible").asBoolean()) why = "not visible";
                    else if (enabled && !last.path("enabled").asBoolean()) why = "disabled";
                    else if (editable && !last.path("editable").asBoolean()) why = "not editable";
                    else if (last.path("obscured").asBoolean()) why = "covered by another element";
                    else return last;
                }
            } catch (PageScriptException e) {
                why = "page is navigating";
                scriptError = e.getMessage();
            }
            if (System.nanoTime() >= deadline) {
                if (last == null && scriptError != null) {
                    throw new ActionFailure(ErrorCode.BROWSER_ERROR, "Could not locate " + loc.describe() + ": " + scriptError);
                }
                boolean missing = last == null || last.path("count").asInt() == 0;
                throw new ActionFailure(missing ? ErrorCode.ELEMENT_NOT_FOUND : ErrorCode.TIMEOUT,
                        missing ? "No element matches " + loc.describe() : "Element " + loc.describe() + " is " + why);
            }
            sleep(POLL);
        }
    }

    private void mouse(String type, double x, double y, int clickCount, String button) {
        ObjectNode p = obj().put("type", type).put("x", x).put("y", y).put("button", button).put("clickCount", clickCount);
        if (type.equals("mousePressed")) p.put("buttons", 1);
        cdp("Input.dispatchMouseEvent", p);
    }

    private void click(double x, double y, int count) {
        mouse("mouseMoved", x, y, 0, "none");
        for (int i = 1; i <= count; i++) {
            mouse("mousePressed", x, y, i, "left");
            mouse("mouseReleased", x, y, i, "left");
        }
    }

    private void key(String type, Keys.Key k, int modifiers) {
        ObjectNode p = obj().put("type", type).put("key", k.key()).put("code", k.code())
                .put("windowsVirtualKeyCode", k.keyCode()).put("nativeVirtualKeyCode", k.keyCode()).put("modifiers", modifiers);
        if (type.equals("keyDown") && k.text() != null && (modifiers & (2 | 4)) == 0) p.put("text", k.text()).put("unmodifiedText", k.text());
        cdp("Input.dispatchKeyEvent", p);
    }

    private void press(String spec) {
        Keys.Press press;
        try {
            press = Keys.parse(spec);
        } catch (IllegalArgumentException e) {
            throw new ActionFailure(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        int mask = 0;
        for (Keys.Key m : press.modifiers()) key("rawKeyDown", m, mask);
        mask = press.modifierMask();
        boolean printable = press.key().text() != null && (mask & (2 | 4)) == 0;
        key(printable ? "keyDown" : "rawKeyDown", press.key(), mask);
        key("keyUp", press.key(), mask);
        for (Keys.Key m : press.modifiers()) key("keyUp", m, 0);
    }

    @Override
    public ActionResult perform(ActionRequest r, Duration timeout) {
        requireOwnTab(r.tabId());
        requireAllowedPage();
        return locked(() -> {
            long t0 = System.nanoTime();
            long deadline = t0 + timeout.toNanos();
            String before = url;
            long nav0 = navStarted.get();
            long loads0 = loads.get();
            try {
                Object value = execute(r, deadline);
                if (!r.type().isReadOnly() && r.type() != ActionType.GET_TEXT && r.type() != ActionType.IS_VISIBLE) {
                    settleNavigation(nav0, loads0);
                }
                return ActionResult.ok(r.type(), (System.nanoTime() - t0) / 1_000_000, value, url, !url.equals(before));
            } catch (ActionFailure f) {
                return ActionResult.failed(r.type(), (System.nanoTime() - t0) / 1_000_000, f.code, f.getMessage(), url);
            } catch (PageScriptException e) {
                return ActionResult.failed(r.type(), (System.nanoTime() - t0) / 1_000_000, ErrorCode.BROWSER_ERROR, e.getMessage(), url);
            }
        });
    }

    /** If the action started a navigation, wait (bounded) for it to load like Playwright does for clicks. */
    private void settleNavigation(long nav0, long loads0) {
        sleep(Duration.ofMillis(120));
        if (navStarted.get() > nav0) {
            await(loads, loads0, System.nanoTime() + spec.navigationTimeout().toNanos());
            refreshTitle();
        }
    }

    private Object execute(ActionRequest r, long deadline) {
        Locator loc = r.locator() == null || r.locator().isEmpty() ? null : r.locator();
        switch (r.type()) {
            case CLICK, DOUBLE_CLICK -> {
                JsonNode t = actionable(loc, deadline, true, false);
                click(t.path("x").asDouble(), t.path("y").asDouble(), r.type() == ActionType.CLICK ? 1 : 2);
            }
            case HOVER -> {
                JsonNode t = actionable(loc, deadline, false, false);
                mouse("mouseMoved", t.path("x").asDouble(), t.path("y").asDouble(), 0, "none");
            }
            case CHECK, UNCHECK -> {
                boolean want = r.type() == ActionType.CHECK;
                JsonNode t = actionable(loc, deadline, true, false);
                if (t.path("checked").isBoolean() && t.path("checked").asBoolean() != want) {
                    click(t.path("x").asDouble(), t.path("y").asDouble(), 1);
                    JsonNode after = eval(PageScript.call("target", json(loc), "false"));
                    if (after.path("checked").asBoolean() != want) {
                        throw new ActionFailure(ErrorCode.BROWSER_ERROR, "Clicking did not change the checked state");
                    }
                } else if (!t.path("checked").isBoolean()) {
                    throw new ActionFailure(ErrorCode.VALIDATION_FAILED, "Element is not a checkbox or radio");
                }
            }
            case FILL, CLEAR -> {
                actionable(loc, deadline, true, true);
                eval(PageScript.call("fill", json(loc), json(r.type() == ActionType.CLEAR ? "" : r.value())));
            }
            case TYPE -> {
                actionable(loc, deadline, true, true);
                eval(PageScript.call("focusEl", json(loc)));
                for (char c : r.value().toCharArray()) {
                    Keys.Key k = Keys.character(c);
                    key("keyDown", k, 0);
                    key("keyUp", k, 0);
                }
            }
            case PRESS -> {
                if (loc != null) {
                    actionable(loc, deadline, false, false);
                    eval(PageScript.call("focusEl", json(loc)));
                }
                press(r.key());
            }
            case SELECT -> {
                actionable(loc, deadline, true, false);
                List<String> values = r.values() != null && !r.values().isEmpty() ? r.values() : List.of(r.value());
                return mapper.convertValue(eval(PageScript.call("selectOptions", json(loc), json(values))), List.class);
            }
            case SCROLL -> {
                if (loc != null) actionable(loc, deadline, false, false);
                return mapper.convertValue(eval(PageScript.call("scrollEl", loc == null ? "null" : json(loc),
                        String.valueOf(r.deltaX() == null ? 0 : r.deltaX()), String.valueOf(r.deltaY() == null ? 0 : r.deltaY()))), Map.class);
            }
            case DRAG_AND_DROP -> {
                JsonNode from = actionable(loc, deadline, true, false);
                JsonNode to = actionable(r.target(), deadline, false, false);
                double x1 = from.path("x").asDouble(), y1 = from.path("y").asDouble();
                double x2 = to.path("x").asDouble(), y2 = to.path("y").asDouble();
                mouse("mouseMoved", x1, y1, 0, "none");
                mouse("mousePressed", x1, y1, 1, "left");
                for (int i = 1; i <= 5; i++) {
                    ObjectNode p = obj().put("type", "mouseMoved").put("x", x1 + (x2 - x1) * i / 5).put("y", y1 + (y2 - y1) * i / 5)
                            .put("button", "left").put("buttons", 1);
                    cdp("Input.dispatchMouseEvent", p);
                }
                mouse("mouseReleased", x2, y2, 1, "left");
            }
            case FOCUS -> {
                actionable(loc, deadline, false, false);
                eval(PageScript.call("focusEl", json(loc)));
            }
            case GET_TEXT -> {
                attached(loc, deadline);
                return eval(PageScript.call("getText", json(loc))).asText();
            }
            case GET_ATTRIBUTE -> {
                attached(loc, deadline);
                JsonNode v = eval(PageScript.call("getAttr", json(loc), json(r.attribute())));
                return v.isNull() || v.isMissingNode() ? null : v.asText();
            }
            case IS_VISIBLE -> {
                return eval(PageScript.call("isVisible", json(loc))).asBoolean();
            }
            case CLICK_AT -> click(r.x(), r.y(), 1);
        }
        return null;
    }

    private void attached(Locator loc, long deadline) {
        while (true) {
            JsonNode t = eval(PageScript.call("target", json(loc), "false"));
            int count = t.path("count").asInt();
            if (count > 1 && loc.nth() == null) {
                throw new ActionFailure(ErrorCode.VALIDATION_FAILED, loc.describe() + " matched " + count + " elements; use nth");
            }
            if (count == 1) return;
            if (System.nanoTime() >= deadline) throw new ActionFailure(ErrorCode.ELEMENT_NOT_FOUND, "No element matches " + loc.describe());
            sleep(POLL);
        }
    }

    // ---------------------------------------------------------------- inspection

    @Override
    public DomSnapshot snapshot(SnapshotRequest request) {
        requireOwnTab(request.tabId());
        requireAllowedPage();
        refreshTitle();
        if (request.mode() == SnapshotMode.ACCESSIBILITY) {
            String aria = eval(PageScript.call("ariaOutline", json(request.root()))).asText("");
            return new DomSnapshot(title, url, tabId, SnapshotMode.ACCESSIBILITY, 0, false, List.of(), aria);
        }
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("mode", request.mode().name());
        args.put("root", request.root());
        args.put("maxElements", request.maxElements());
        args.put("includeHidden", request.includeHidden());
        args.put("role", request.role());
        args.put("text", request.text());
        @SuppressWarnings("unchecked")
        Map<String, Object> res = mapper.convertValue(eval(PageScript.call("snapshot", json(args))), Map.class);
        return PageResults.snapshot(res, tabId, request.mode());
    }

    @Override
    public ElementDetails inspect(InspectRequest request) {
        requireOwnTab(request.tabId());
        requireAllowedPage();
        JsonNode v = eval(PageScript.wrap("H => { const L = " + json(request.locator()) + "; const els = H.candidates(L); "
                + "const el = L.nth != null ? els[L.nth] : els[0]; return el ? H.inspect(el, " + json(request.styles() == null
                ? List.of() : request.styles()) + ") : null; }"));
        if (v.isNull() || v.isMissingNode()) {
            throw new BridgeException(ErrorCode.ELEMENT_NOT_FOUND, "No element matches " + request.locator().describe());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = mapper.convertValue(v, Map.class);
        return PageResults.details(m);
    }

    @Override
    public AccessibilityReport accessibility(String id, String root) {
        requireOwnTab(id);
        requireAllowedPage();
        String aria = eval(PageScript.call("ariaOutline", json(root))).asText("");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = mapper.convertValue(eval(PageScript.call("audit")), List.class);
        List<AccessibilityIssue> issues = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            issues.add(new AccessibilityIssue(PageResults.str(m.get("rule")), PageResults.str(m.get("severity")),
                    PageResults.str(m.get("message")), PageResults.str(m.get("reference"))));
        }
        return new AccessibilityReport(tabId, url, aria, issues);
    }

    // ---------------------------------------------------------------- screenshots & viewport

    @Override
    public CapturedImage screenshot(ScreenshotRequest r) {
        requireOwnTab(r.tabId());
        requireAllowedPage();
        return locked(() -> {
            ImageFormat format = r.format() == null ? ImageFormat.PNG : r.format();
            boolean mask = r.maskSensitive() == null || r.maskSensitive();
            if (mask) {
                eval("(() => { if (!document.getElementById('" + MASK_STYLE_ID + "')) { const s = document.createElement('style'); "
                        + "s.id = '" + MASK_STYLE_ID + "'; s.textContent = " + json(MASK_CSS) + "; "
                        + "(document.head || document.documentElement).appendChild(s); } return true; })()");
            }
            try {
                ObjectNode p = obj().put("format", format == ImageFormat.JPEG ? "jpeg" : "png");
                if (format == ImageFormat.JPEG) p.put("quality", r.quality() == null ? 80 : r.quality());
                if (r.locator() != null && !r.locator().isEmpty()) {
                    JsonNode box = eval(PageScript.wrap("H => { const L = " + json(r.locator()) + "; const els = H.candidates(L); "
                            + "const el = L.nth != null ? els[L.nth] : els[0]; if (!el) return null; el.scrollIntoView({block: 'center'}); "
                            + "const b = el.getBoundingClientRect(); return {x: b.left + scrollX, y: b.top + scrollY, width: b.width, height: b.height}; }"));
                    if (box.isNull() || box.isMissingNode()) {
                        throw new BridgeException(ErrorCode.ELEMENT_NOT_FOUND, "No element matches " + r.locator().describe());
                    }
                    p.set("clip", ((ObjectNode) box).put("scale", 1));
                    p.put("captureBeyondViewport", true);
                } else if (Boolean.TRUE.equals(r.fullPage())) {
                    JsonNode metrics = cdp("Page.getLayoutMetrics", obj());
                    JsonNode size = metrics.has("cssContentSize") ? metrics.get("cssContentSize") : metrics.path("contentSize");
                    p.set("clip", obj().put("x", 0).put("y", 0).put("width", size.path("width").asDouble())
                            .put("height", size.path("height").asDouble()).put("scale", 1));
                    p.put("captureBeyondViewport", true);
                }
                JsonNode res = cdp("Page.captureScreenshot", p, Duration.ofSeconds(20));
                return new CapturedImage(Base64.getDecoder().decode(res.path("data").asText()), format, url);
            } finally {
                if (mask) {
                    try {
                        eval("(() => { const s = document.getElementById('" + MASK_STYLE_ID + "'); if (s) s.remove(); return true; })()");
                    } catch (RuntimeException ignored) {
                        // page changed
                    }
                }
            }
        });
    }

    @Override
    public void setViewport(String id, Viewport viewport) {
        requireOwnTab(id);
        cdp("Emulation.setDeviceMetricsOverride", obj().put("width", viewport.width()).put("height", viewport.height())
                .put("deviceScaleFactor", 0).put("mobile", false));
    }

    @Override
    public Object evaluate(String id, String expression) {
        requireOwnTab(id);
        requireAllowedPage();
        return mapper.convertValue(eval(expression), Object.class);
    }

    @Override
    public void restart() {
        conn.call("tab.reattach", obj().put("tabId", chromeTabId), spec.operationTimeout().plusSeconds(5));
        connected = true;
        init();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        conn.forget(chromeTabId, this);
        try {
            conn.notify("tab.unshare", obj().put("tabId", chromeTabId));
        } catch (RuntimeException ignored) {
            // extension gone
        }
    }
}
