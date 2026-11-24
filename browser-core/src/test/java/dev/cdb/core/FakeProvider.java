package dev.cdb.core;

import dev.cdb.core.spi.BrowserEventListener;
import dev.cdb.core.spi.BrowserProvider;
import dev.cdb.core.spi.BrowserSessionDriver;
import dev.cdb.core.spi.CapturedImage;
import dev.cdb.core.spi.SessionLaunchSpec;
import dev.cdb.protocol.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/** In-memory provider used by core unit tests. */
public class FakeProvider implements BrowserProvider {
    public final List<FakeDriver> drivers = new ArrayList<>();
    public boolean failNextOpen;

    @Override
    public ProviderInfo info() {
        return new ProviderInfo("fake", "Fake", "1", "fakium", "1.0");
    }

    @Override
    public BrowserSessionDriver open(SessionLaunchSpec spec, BrowserEventListener listener) {
        if (failNextOpen) {
            failNextOpen = false;
            throw new IllegalStateException("boom");
        }
        FakeDriver d = new FakeDriver(spec, listener);
        drivers.add(d);
        return d;
    }

    @Override
    public void close() {
    }

    public static byte[] png(int w, int h, Color color) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(color);
            g.fillRect(0, 0, w, h);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class FakeDriver implements BrowserSessionDriver {
        public final SessionLaunchSpec spec;
        public final BrowserEventListener listener;
        public final List<ActionRequest> actions = new ArrayList<>();
        public String url = "about:blank";
        public String title = "";
        public boolean connected = true;
        public boolean closed;
        public int restarts;
        public Color screenshotColor = Color.WHITE;
        public String text = "Welcome to the Dashboard";
        private final AtomicInteger tabs = new AtomicInteger(1);

        FakeDriver(SessionLaunchSpec spec, BrowserEventListener listener) {
            this.spec = spec;
            this.listener = listener;
        }

        @Override public String contextId() { return "ctx-" + spec.sessionId(); }
        @Override public ProviderInfo providerInfo() { return new ProviderInfo("fake", "Fake", "1", "fakium", "1.0"); }
        @Override public boolean isConnected() { return connected; }
        @Override public List<TabInfo> tabs() { return List.of(new TabInfo("tab-1", url, title, true)); }
        @Override public String activeTabId() { return "tab-1"; }
        @Override public TabInfo openTab(String u) { return new TabInfo("tab-" + tabs.incrementAndGet(), u == null ? "about:blank" : u, "", true); }
        @Override public void closeTab(String tabId) { }
        @Override public TabInfo activateTab(String tabId) { return new TabInfo(tabId, url, title, true); }

        @Override
        public NavigationResult navigate(String tabId, String u, WaitUntil waitUntil, Duration timeout) {
            if (!spec.guard().isAllowed(u)) throw new BridgeException(ErrorCode.URL_NOT_ALLOWED, "blocked");
            url = u;
            title = u.contains("dashboard") ? "Dashboard" : "Login";
            listener.onNavigation("tab-1", u, BrowserEventListener.NavigationPhase.COMPLETED, null);
            return new NavigationResult("tab-1", u, title, 200, 5);
        }

        @Override public NavigationResult reload(String tabId, Duration t) { return new NavigationResult("tab-1", url, title, 200, 1); }
        @Override public NavigationResult back(String tabId, Duration t) { return new NavigationResult("tab-1", url, title, null, 1); }
        @Override public NavigationResult forward(String tabId, Duration t) { return new NavigationResult("tab-1", url, title, null, 1); }
        @Override public PageInfo stop(String tabId) { return pageInfo(tabId); }
        @Override public PageInfo pageInfo(String tabId) { return new PageInfo("tab-1", url, title, "complete"); }

        @Override
        public WaitResult waitFor(WaitRequest request, Duration timeout) {
            if (request.text() != null) return new WaitResult(text.contains(request.text()), 1, null);
            if (request.locator() != null && request.locator().text() != null) {
                boolean visible = text.contains(request.locator().text());
                boolean ok = request.state() == ElementState.HIDDEN ? !visible : visible;
                return new WaitResult(ok, 1, ok ? null : "not " + request.state());
            }
            if (request.urlPattern() != null) {
                String p = request.urlPattern().replace("**", "");
                return new WaitResult(url.contains(p), 1, null);
            }
            return new WaitResult(true, 1, null);
        }

        @Override
        public ActionResult perform(ActionRequest request, Duration timeout) {
            actions.add(request);
            if (request.locator() != null && "missing".equals(request.locator().testId())) {
                return ActionResult.failed(request.type(), 1, ErrorCode.ELEMENT_NOT_FOUND, "No element for testId=missing", url);
            }
            if (request.type() == ActionType.CLICK && request.locator() != null && "Sign In".equals(request.locator().name())) {
                navigate(null, url.replace("/login", "/dashboard"), WaitUntil.LOAD, timeout);
                return ActionResult.ok(request.type(), 1, null, url, true);
            }
            Object value = request.type() == ActionType.GET_TEXT ? text + " token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlMTIz" : null;
            return ActionResult.ok(request.type(), 1, value, url, false);
        }

        @Override
        public DomSnapshot snapshot(SnapshotRequest request) {
            return new DomSnapshot(title, url, "tab-1", request.mode(), 1, false, List.of(new DomElement("element-1", null, 0,
                    "button", "button", "Sign In", null, true, true, false, null, null, null, null)), null);
        }

        @Override
        public ElementDetails inspect(InspectRequest request) {
            return new ElementDetails("element-1", "button", "button", "Sign In", "Sign In", true, true, false, null, null, null,
                    null, null, List.of("body"), 0);
        }

        @Override
        public AccessibilityReport accessibility(String tabId, String root) {
            return new AccessibilityReport("tab-1", url, "- button \"Sign In\"", List.of());
        }

        @Override
        public CapturedImage screenshot(ScreenshotRequest request) {
            return new CapturedImage(png(40, 30, screenshotColor), ImageFormat.PNG, url);
        }

        @Override public void setViewport(String tabId, Viewport viewport) { }
        @Override public Object evaluate(String tabId, String expression) { return 42; }
        @Override public void restart() { restarts++; connected = true; }
        @Override public void close() { closed = true; }
    }
}
