package dev.cdb.provider.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.cdb.core.spi.BrowserEventListener;
import dev.cdb.core.spi.BrowserSessionDriver;
import dev.cdb.core.spi.CapturedImage;
import dev.cdb.core.spi.NavigationGuard;
import dev.cdb.core.spi.SessionLaunchSpec;
import dev.cdb.protocol.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real-Chromium checks of the provider. Run with -Dcdb.browserTests=true (downloads Chromium on first run). */
@EnabledIfSystemProperty(named = "cdb.browserTests", matches = "true")
class PlaywrightProviderBrowserTest {
    static HttpServer server;
    static String base;
    static PlaywrightProvider provider;
    static final List<ConsoleEntry> console = new CopyOnWriteArrayList<>();
    static final List<NetworkEntry> network = new CopyOnWriteArrayList<>();
    static final List<String> blocked = new CopyOnWriteArrayList<>();

    static final String PAGE = """
            <!doctype html><html lang="en"><head><title>Login</title></head><body>
            <main><h1>Sign in</h1>
            <form id="f" onsubmit="event.preventDefault(); document.getElementById('out').textContent='Welcome ' + document.getElementById('email').value;">
              <label for="email">Email</label><input id="email" name="email" type="email">
              <label for="pw">Password</label><input id="pw" name="password" type="password" data-testid="password">
              <select id="country" aria-label="Country"><option value="nl">Netherlands</option><option value="de">Germany</option></select>
              <button type="submit">Sign In</button>
            </form>
            <p id="out"></p>
            <a href="https://example.com/">external</a>
            </main>
            <script>
              console.log('page ready');
              fetch('/api/fail').then(r => console.error('API failed with ' + r.status));
              setTimeout(() => { throw new Error('Boom from timer'); }, 10);
              setTimeout(() => Promise.reject(new Error('Rejected promise')), 20);
            </script>
            </body></html>
            """;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b;
            int status = 200;
            if (ex.getRequestURI().getPath().equals("/api/fail")) {
                b = "{\"error\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
                status = 500;
                ex.getResponseHeaders().add("Content-Type", "application/json");
            } else {
                b = PAGE.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html");
            }
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        provider = new PlaywrightProvider(PlaywrightSettings.defaults());
    }

    @AfterAll
    static void stop() {
        if (provider != null) provider.close();
        if (server != null) server.stop(0);
    }

    static SessionLaunchSpec spec(String id) {
        NavigationGuard guard = url -> url.startsWith(base) || url.startsWith("about:");
        return new SessionLaunchSpec(id, true, new Viewport(1280, 800), null, null, null, SessionIsolation.SHARED, null, null, null,
                null, false, guard, Duration.ofSeconds(5), Duration.ofSeconds(20), false, 0);
    }

    static final BrowserEventListener LISTENER = new BrowserEventListener() {
        public void onConsole(ConsoleEntry e) { console.add(e); }
        public void onNetwork(NetworkEntry e) { network.add(e); }
        public void onTabCreated(TabInfo tab) { }
        public void onTabClosed(String tabId) { }
        public void onNavigation(String tabId, String url, NavigationPhase phase, String error) { }
        public void onNavigationBlocked(String tabId, String url) { blocked.add(url); }
        public void onDisconnected(String reason) { }
    };

    @Test
    void endToEnd() throws Exception {
        BrowserSessionDriver d = provider.open(spec("s1"), LISTENER);
        try {
            NavigationResult nav = d.navigate(null, base + "/login", WaitUntil.LOAD, Duration.ofSeconds(20));
            assertThat(nav.status()).isEqualTo(200);
            assertThat(nav.title()).isEqualTo("Login");

            DomSnapshot snap = d.snapshot(new SnapshotRequest(null, SnapshotMode.INTERACTIVE, null, 100, null, null, null));
            assertThat(snap.elements()).extracting(DomElement::name).contains("Email", "Password", "Sign In");
            DomElement button = snap.elements().stream().filter(e -> "Sign In".equals(e.name())).findFirst().orElseThrow();
            assertThat(button.role()).isEqualTo("button");

            assertThat(d.perform(ActionRequest.fill(Locator.label("Email"), "qa@example.test"), Duration.ofSeconds(5)).success()).isTrue();
            assertThat(d.perform(ActionRequest.fill(Locator.testId("password"), "S3cret!"), Duration.ofSeconds(5)).success()).isTrue();
            assertThat(d.perform(ActionRequest.select(Locator.label("Country"), List.of("Germany")), Duration.ofSeconds(5)).success()).isTrue();

            DomSnapshot afterFill = d.snapshot(new SnapshotRequest(null, SnapshotMode.FULL, null, 500, null, null, null));
            assertThat(afterFill.elements()).noneMatch(e -> "S3cret!".equals(e.value()));
            assertThat(afterFill.elements()).anyMatch(e -> "[REDACTED]".equals(e.value()));
            ActionResult pwValue = d.perform(ActionRequest.attribute(Locator.testId("password"), "value"), Duration.ofSeconds(5));
            assertThat(pwValue.value()).isEqualTo("[REDACTED]");

            ActionResult click = d.perform(ActionRequest.of(ActionType.CLICK, Locator.ref(button.reference())), Duration.ofSeconds(5));
            assertThat(click.success()).as(click.message()).isTrue();
            ActionResult text = d.perform(ActionRequest.of(ActionType.GET_TEXT, Locator.css("#out")), Duration.ofSeconds(5));
            assertThat(text.value()).isEqualTo("Welcome qa@example.test");

            ActionResult missing = d.perform(ActionRequest.of(ActionType.CLICK, Locator.testId("nope")), Duration.ofSeconds(1));
            assertThat(missing.success()).isFalse();
            assertThat(missing.errorCode()).isEqualTo("ELEMENT_NOT_FOUND");

            ElementDetails details = d.inspect(new InspectRequest(null, Locator.label("Password"), null));
            assertThat(details.formField().sensitive()).isTrue();
            assertThat(details.formField().value()).isEqualTo("[REDACTED]");
            assertThat(details.computedStyles()).containsKey("display");

            AccessibilityReport a11y = d.accessibility(null, null);
            assertThat(a11y.ariaSnapshot()).contains("button \"Sign In\"");

            WaitResult wait = d.waitFor(new WaitRequest(null, null, null, null, null, "Welcome", 2000), Duration.ofSeconds(2));
            assertThat(wait.satisfied()).isTrue();
            WaitResult timeout = d.waitFor(new WaitRequest(null, Locator.text("never-there"), ElementState.VISIBLE, null, null, null, 300),
                    Duration.ofMillis(300));
            assertThat(timeout.satisfied()).isFalse();

            CapturedImage shot = d.screenshot(ScreenshotRequest.viewport("x"));
            assertThat(shot.bytes().length).isGreaterThan(1000);

            Thread.sleep(800); // let the pump deliver remaining events
            assertThat(console).anyMatch(c -> c.message().contains("page ready"));
            assertThat(console).anyMatch(c -> c.kind() == ConsoleKind.PAGE_ERROR && c.message().contains("Boom from timer")
                    && !c.stack().isEmpty());
            assertThat(console).anyMatch(c -> c.kind() == ConsoleKind.UNHANDLED_REJECTION && c.message().contains("Rejected promise"));
            assertThat(console).noneMatch(c -> c.kind() == ConsoleKind.PAGE_ERROR && c.message().contains("Rejected promise"));
            assertThat(network).anyMatch(n -> n.url().endsWith("/api/fail") && n.state() == NetworkState.COMPLETED
                    && Integer.valueOf(500).equals(n.status()));

            // URL guard: clicking an external link is blocked at the network layer.
            d.perform(ActionRequest.of(ActionType.CLICK, Locator.text("external")), Duration.ofSeconds(5));
            Thread.sleep(800);
            assertThat(d.pageInfo(null).url()).isEqualTo(base + "/login");
            assertThat(blocked).anyMatch(u -> u.startsWith("https://example.com"));
            assertThat(d.pageInfo(null).url()).doesNotContain("example.com");

            TabInfo second = d.openTab(base + "/login");
            assertThat(d.tabs()).hasSize(2);
            d.closeTab(second.tabId());
            assertThat(d.tabs()).hasSize(1);

            // Isolation: a second session does not share storage.
            d.evaluate(null, "localStorage.setItem('k', 'v')");
            BrowserSessionDriver other = provider.open(spec("s2"), BrowserEventListener.NOOP);
            try {
                other.navigate(null, base + "/login", WaitUntil.LOAD, Duration.ofSeconds(20));
                assertThat(other.evaluate(null, "localStorage.getItem('k')")).isNull();
                assertThat(other.contextId()).isNotEqualTo(d.contextId());
            } finally {
                other.close();
            }

            d.restart();
            assertThat(d.isConnected()).isTrue();
            assertThat(d.pageInfo(null).url()).startsWith(base);
        } finally {
            d.close();
        }
    }
}
