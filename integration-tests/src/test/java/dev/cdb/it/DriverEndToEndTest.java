package dev.cdb.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cdb.client.BrowserDriverClient;
import dev.cdb.client.DriverApiException;
import dev.cdb.driver.DriverApplication;
import dev.cdb.driver.security.TokenService;
import dev.cdb.protocol.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** MVP acceptance checks against a real driver, real Chromium and the sample application. */
@SpringBootTest(classes = DriverApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cdb.data-dir=target/it-data", "cdb.sessions.max=5"})
class DriverEndToEndTest {
    static SampleApp app;

    @LocalServerPort
    int port;
    @Autowired
    TokenService tokens;

    BrowserDriverClient admin;
    BrowserDriverClient agent;

    @BeforeAll
    static void startApp() throws Exception {
        app = SampleApp.start();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) app.close();
    }

    @BeforeEach
    void clients() {
        admin = new BrowserDriverClient("http://127.0.0.1:" + port, tokens.adminToken(), "it-admin");
        agent = new BrowserDriverClient("http://127.0.0.1:" + port, tokens.agentToken(), "it-agent");
    }

    static <T> T eventually(java.util.function.Supplier<T> supplier, java.util.function.Predicate<T> done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        T v = supplier.get();
        while (!done.test(v) && System.currentTimeMillis() < deadline) {
            Thread.sleep(150);
            v = supplier.get();
        }
        return v;
    }

    @Test
    void loginFlowConsoleNetworkDomAndScreenshots() throws Exception {
        SessionInfo s = agent.createSession(SessionCreateRequest.builder().projectId("it").build());
        String id = s.sessionId();
        try {
            assertThat(s.state()).isEqualTo(SessionState.RUNNING);
            assertThat(s.provider().browserVersion()).isNotBlank();

            NavigationResult nav = agent.navigate(id, new NavigateRequest(app.url + "/login"));
            assertThat(nav.status()).isEqualTo(200);
            assertThat(nav.title()).isEqualTo("Login");

            DomSnapshot snap = agent.dom(id, new SnapshotRequest(null, SnapshotMode.INTERACTIVE, null, null, null, null, null));
            assertThat(snap.elements()).extracting(DomElement::name).contains("Email", "Password", "Sign In");
            String emailRef = snap.elements().stream().filter(e -> "Email".equals(e.name())).findFirst().orElseThrow().reference();

            // Client-side validation errors first.
            agent.perform(id, ActionRequest.of(ActionType.CLICK, Locator.role("button", "Sign In")));
            ActionResult err = agent.perform(id, ActionRequest.of(ActionType.GET_TEXT, Locator.css("#email-error")));
            assertThat(err.value()).isEqualTo("Enter a valid email address");

            assertThat(agent.perform(id, ActionRequest.fill(Locator.ref(emailRef), "qa@example.test")).success()).isTrue();
            assertThat(agent.perform(id, ActionRequest.fill(Locator.testId("password"), "demo-password")).success()).isTrue();
            DomSnapshot filled = agent.dom(id, new SnapshotRequest(null, SnapshotMode.FULL, null, null, null, null, null));
            assertThat(filled.elements()).noneMatch(e -> "demo-password".equals(e.value()));

            ActionResult click = agent.perform(id, ActionRequest.of(ActionType.CLICK, Locator.role("button", "Sign In")));
            assertThat(click.success()).as(click.message()).isTrue();
            WaitResult onDashboard = agent.waitFor(id, new WaitRequest(null, null, null, null, "**/dashboard", null, 10_000));
            assertThat(onDashboard.satisfied()).isTrue();
            WaitResult visitors = agent.waitFor(id, new WaitRequest(null, null, null, null, null, "1284", 10_000));
            assertThat(visitors.satisfied()).isTrue();

            // Console: runtime error mapped to the TypeScript source, plus console.error from the failed API call.
            EventPage<ConsoleEntry> errors = eventually(
                    () -> agent.console(id, new ConsoleQuery(null, ConsoleLevel.ERROR, null, null, null, null, 100)),
                    p -> p.items().stream().anyMatch(e -> e.kind() == ConsoleKind.PAGE_ERROR && e.originalSource() != null));
            ConsoleEntry runtime = errors.items().stream().filter(e -> e.kind() == ConsoleKind.PAGE_ERROR).findFirst().orElseThrow();
            assertThat(runtime.message()).contains("forEach");
            assertThat(runtime.source().file()).isEqualTo("dashboard.js");
            assertThat(runtime.source().line()).isEqualTo(29);
            assertThat(runtime.originalSource().file()).isEqualTo("src/dashboard.ts");
            assertThat(runtime.originalSource().line()).isEqualTo(27);
            assertThat(runtime.stack()).isNotEmpty();
            assertThat(errors.items()).anyMatch(e -> e.message().contains("Failed to load application data"));

            // Network: deliberate 500, redacted auth header on the stats call, login POST.
            EventPage<NetworkEntry> failed = agent.network(id, NetworkQuery.failed());
            assertThat(failed.items()).anyMatch(e -> e.url().endsWith("/api/notifications") && e.status() == 500);
            EventPage<NetworkEntry> stats = agent.network(id, new NetworkQuery(null, true, null, null, null, "/api/stats", null, null, null));
            assertThat(stats.items()).isNotEmpty();
            assertThat(stats.items().get(0).requestHeaders().get("authorization")).isEqualTo("[REDACTED]");
            assertThat(agent.network(id, new NetworkQuery(null, null, null, "200", null, "/api/login", null, null, null)).items())
                    .hasSize(1);

            // Inspection & accessibility.
            ElementDetails heading = agent.inspect(id, new InspectRequest(null, Locator.role("heading", "Dashboard"), List.of("font-size")));
            assertThat(heading.tag()).isEqualTo("h1");
            assertThat(heading.computedStyles()).containsKey("font-size");
            AccessibilityReport a11y = agent.accessibility(id, null, null);
            assertThat(a11y.ariaSnapshot()).contains("heading \"Dashboard\"");

            // Screenshots: viewport, full page, element, mobile preset.
            ScreenshotInfo shot = agent.screenshot(id, new ScreenshotRequest(null, "dashboard", false, null, ImageFormat.PNG, null, null, true, false));
            assertThat(shot.width()).isEqualTo(1280);
            byte[] png = agent.screenshotImage(shot.screenshotId());
            assertThat(png[1]).isEqualTo((byte) 'P');
            ScreenshotInfo mobile = agent.screenshot(id, new ScreenshotRequest(null, "mobile", false, null, ImageFormat.PNG, null,
                    ViewportPreset.MOBILE, true, false));
            assertThat(mobile.width()).isEqualTo(390);
            ScreenshotInfo element = agent.screenshot(id, new ScreenshotRequest(null, "card", false, Locator.testId("visitors"),
                    ImageFormat.JPEG, 80, null, true, true));
            assertThat(element.base64()).isNotBlank();
            assertThat(agent.screenshots(id)).hasSizeGreaterThanOrEqualTo(3);
        } finally {
            agent.closeSession(id);
        }
        assertThatThrownBy(() -> agent.tabs(id)).isInstanceOf(DriverApiException.class)
                .satisfies(e -> assertThat(((DriverApiException) e).code()).isEqualTo("SESSION_NOT_FOUND"));
    }

    @Test
    void sessionsAreIsolated() {
        String a = agent.createSession(null).sessionId();
        String b = agent.createSession(null).sessionId();
        try {
            agent.navigate(a, new NavigateRequest(app.url + "/login"));
            agent.navigate(b, new NavigateRequest(app.url + "/login"));
            agent.perform(a, ActionRequest.fill(Locator.label("Email"), "qa@example.test"));
            agent.perform(a, ActionRequest.fill(Locator.testId("password"), "demo-password"));
            agent.perform(a, ActionRequest.of(ActionType.CLICK, Locator.role("button", "Sign In")));
            agent.waitFor(a, new WaitRequest(null, null, null, null, "**/dashboard", null, 10_000));
            agent.navigate(b, new NavigateRequest(app.url + "/dashboard"));
            // Session b has no token: stats call is unauthorised there, but succeeded in a.
            WaitResult bUnauthorised = agent.waitFor(b, new WaitRequest(null, null, null, null, null, "1284", 1500));
            assertThat(bUnauthorised.satisfied()).isFalse();
            assertThat(agent.network(b, new NetworkQuery(null, null, null, "401", null, "/api/stats", null, null, null)).items()).hasSize(1);
            assertThat(agent.getSession(a).contextId()).isNotEqualTo(agent.getSession(b).contextId());

            // Another agent identity cannot see these sessions.
            BrowserDriverClient other = new BrowserDriverClient("http://127.0.0.1:" + port, tokens.agentToken(), "other-agent");
            assertThat(other.listSessions()).isEmpty();
            assertThatThrownBy(() -> other.dom(a, SnapshotRequest.of(SnapshotMode.COMPACT))).isInstanceOf(DriverApiException.class);
        } finally {
            agent.closeSession(a);
            agent.closeSession(b);
        }
    }

    @Test
    void urlPolicyBlocksExternalNavigationUntilApproved() {
        String id = admin.createSession(SessionCreateRequest.builder().agentAccess(true).build()).sessionId();
        try {
            assertThatThrownBy(() -> agent.navigate(id, new NavigateRequest("https://example.com/")))
                    .satisfies(e -> assertThat(((DriverApiException) e).code()).isEqualTo("URL_NOT_ALLOWED"));
            assertThatThrownBy(() -> agent.approve(id, new ApprovalRequest(ApprovalKind.ORIGIN, "https://example.com", null)))
                    .satisfies(e -> assertThat(((DriverApiException) e).status()).isEqualTo(403));
            assertThat(admin.approve(id, new ApprovalRequest(ApprovalKind.ORIGIN, "https://example.com", "test")).value())
                    .isEqualTo("https://example.com");
            assertThat(agent.approvals(id)).hasSize(1);
        } finally {
            admin.closeSession(id);
        }
    }

    @Test
    void scenarioRunnerValidatesLoginWithEnvironmentCredentials() {
        String id = agent.createSession(null).sessionId();
        try {
            Scenario sc = new Scenario("Login page validation", app.url, null, null, null, 10_000, true, List.of(
                    step("navigate", Map.of("path", "/login")),
                    new ScenarioStep("fill", null, null, null, Locator.role("textbox", "Email"), null, "${TEST_EMAIL}", null, null, null, null, null, null, null, null),
                    new ScenarioStep("fill", null, null, null, Locator.testId("password"), null, "${TEST_PASSWORD}", null, null, null, null, null, null, null, null),
                    new ScenarioStep("click", null, null, null, Locator.role("button", "Sign In"), null, null, null, null, null, null, null, null, null, null),
                    step("assertUrl", Map.of("urlPattern", "**/dashboard")),
                    step("assertVisible", Map.of("text", "Dashboard")),
                    new ScenarioStep("assertNetworkStatus", null, null, null, null, null, null, null, null, null, "**/api/login", 200, null, null, null),
                    new ScenarioStep("assertNetworkStatus", null, null, null, null, null, null, null, null, null, "**/api/notifications", 500, null, null, null),
                    step("screenshot", Map.of("name", "dashboard-after-login"))));
            ScenarioReport report = agent.runScenario(id, sc);
            assertThat(report.success()).as(report.toString()).isTrue();
            assertThat(report.passed()).isEqualTo(9);
            assertThat(report.failedRequests()).anyMatch(r -> r.contains("/api/notifications"));
            assertThat(report.toString()).doesNotContain("demo-password");

            Scenario failing = new Scenario("Console must be clean", app.url, null, null, null, 5_000, true, List.of(
                    step("navigate", Map.of("path", "/dashboard")),
                    step("wait", Map.of("text", "Notifications")),
                    step("assertNoConsoleErrors", Map.of()),
                    step("screenshot", Map.of("name", "never"))));
            ScenarioReport bad = agent.runScenario(id, failing);
            assertThat(bad.success()).isFalse();
            assertThat(bad.skipped()).isEqualTo(1);
            assertThat(bad.steps().get(2).screenshotId()).isNotNull();
        } finally {
            agent.closeSession(id);
        }
    }

    @Test
    void visualRegressionAndResponsiveLayout() {
        String id = agent.createSession(null).sessionId();
        try {
            agent.navigate(id, new NavigateRequest(app.url + "/register"));
            ScreenshotInfo a = agent.screenshot(id, ScreenshotRequest.viewport("register-a"));
            agent.saveBaseline(new BaselineRequest("it-register", a.screenshotId()));
            ScreenshotInfo b = agent.screenshot(id, ScreenshotRequest.viewport("register-b"));
            CompareResult same = agent.compare(new CompareRequest(b.screenshotId(), null, "it-register", null, null));
            assertThat(same.match()).as(same.message()).isTrue();

            agent.setViewport(id, new ViewportRequest(ViewportPreset.MOBILE, null, null));
            assertThat(agent.perform(id, ActionRequest.of(ActionType.IS_VISIBLE, Locator.role("button", "Open menu"))).value()).isEqualTo(true);
            ScreenshotInfo m = agent.screenshot(id, ScreenshotRequest.viewport("register-mobile"));
            CompareResult diff = agent.compare(new CompareRequest(m.screenshotId(), null, "it-register", null, null));
            assertThat(diff.match()).isFalse();
            assertThat(diff.sizeMismatch()).isTrue();
        } finally {
            agent.closeSession(id);
        }
    }

    @Test
    void accessibilityAuditFindsDeliberateIssues() {
        String id = agent.createSession(null).sessionId();
        try {
            agent.navigate(id, new NavigateRequest(app.url + "/a11y"));
            AccessibilityReport r = agent.accessibility(id, null, null);
            assertThat(r.issues()).extracting(AccessibilityIssue::rule).contains("image-alt", "label", "button-name", "heading-order");
        } finally {
            agent.closeSession(id);
        }
    }

    @Test
    void tabsAndWebSocketEvents() throws Exception {
        List<String> messages = new CopyOnWriteArrayList<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization", "Bearer " + tokens.adminToken())
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/events"), new WebSocket.Listener() {
                    final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            messages.add(buf.toString());
                            buf.setLength(0);
                        }
                        w.request(1);
                        return null;
                    }
                }).get(10, TimeUnit.SECONDS);
        ws.sendText("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"subscribe\",\"params\":{\"events\":[\"browser.*\"]}}", true);
        Thread.sleep(200);
        String id = admin.createSession(null).sessionId();
        try {
            admin.navigate(id, new NavigateRequest(app.url + "/dashboard"));
            TabInfo second = admin.openTab(id, app.url + "/register");
            assertThat(admin.tabs(id)).hasSize(2);
            admin.activateTab(id, admin.tabs(id).get(0).tabId());
            admin.closeTab(id, second.tabId());
            assertThat(admin.tabs(id)).hasSize(1);
            NavigationResult back = admin.back(id, null);
            assertThat(back.url()).contains("about:blank");
            eventually(() -> String.join("\n", messages), all -> all.contains("browser.console.error")
                    && all.contains("browser.network.response") && all.contains("browser.tab.closed"));
            String all = String.join("\n", messages);
            assertThat(all).contains("browser.session.created", "browser.navigation.completed", "browser.console.error",
                    "browser.network.response", "browser.tab.created", "browser.tab.closed");
        } finally {
            admin.closeSession(id);
            ws.abort();
        }
    }

    static ScenarioStep step(String action, Map<String, String> f) {
        return new ScenarioStep(action, null, f.get("path"), f.get("url"), null, f.get("text"), f.get("value"), null, f.get("key"),
                f.get("name"), f.get("urlPattern"), null, f.get("baseline"), null, null);
    }
}
