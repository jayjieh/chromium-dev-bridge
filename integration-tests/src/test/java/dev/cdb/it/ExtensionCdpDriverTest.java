package dev.cdb.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cdb.core.BridgeException;
import dev.cdb.core.Principal;
import dev.cdb.driver.DriverApplication;
import dev.cdb.core.session.SessionManager;
import dev.cdb.protocol.*;
import dev.cdb.provider.extension.ExtensionHub;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The extension provider's CDP implementation against a real Chromium tab (via {@link FakeChromeExtension}), through
 * the real driver's session manager and security policy.
 */
@SpringBootTest(classes = DriverApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cdb.data-dir=target/it-data-ext"})
class ExtensionCdpDriverTest {
    static SampleApp app;

    @Autowired
    SessionManager sessions;
    @Autowired
    ExtensionHub hub;

    final Principal admin = Principal.admin();
    final Principal agent = Principal.agent("claude-code");

    @BeforeAll
    static void startApp() throws Exception {
        app = SampleApp.start();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) app.close();
    }

    String awaitSession() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            List<SessionInfo> list = sessions.list(admin, null);
            if (!list.isEmpty() && list.get(0).state() == SessionState.RUNNING) return list.get(0).sessionId();
            Thread.sleep(100);
        }
        throw new AssertionError("Shared tab did not become a session");
    }

    @Test
    void sharedTabWorksLikeAnySession() throws Exception {
        try (FakeChromeExtension ext = new FakeChromeExtension(hub)) {
            ext.share("about:blank", true);
            String id = awaitSession();
            SessionInfo info = sessions.get(admin, id);
            assertThat(info.provider().id()).isEqualTo("chrome-extension");
            assertThat(info.label()).startsWith("Chrome:");
            assertThat(sessions.list(agent, null)).extracting(SessionInfo::sessionId).containsExactly(id);

            NavigationResult nav = sessions.navigate(agent, id, new NavigateRequest(app.url + "/login"));
            assertThat(nav.status()).isEqualTo(200);
            assertThat(nav.title()).isEqualTo("Login");

            DomSnapshot snap = sessions.snapshot(agent, id, SnapshotRequest.of(SnapshotMode.INTERACTIVE));
            assertThat(snap.elements()).extracting(DomElement::name).contains("Email", "Password", "Sign In");
            String emailRef = snap.elements().stream().filter(e -> "Email".equals(e.name())).findFirst().orElseThrow().reference();

            // Validation messages via a real mouse click.
            assertThat(sessions.perform(agent, id, ActionRequest.of(ActionType.CLICK, Locator.role("button", "Sign In"))).success()).isTrue();
            assertThat(sessions.perform(agent, id, ActionRequest.of(ActionType.GET_TEXT, Locator.css("#email-error"))).value())
                    .isEqualTo("Enter a valid email address");

            assertThat(sessions.perform(agent, id, ActionRequest.fill(Locator.ref(emailRef), "qa@example.test")).success()).isTrue();
            ActionResult typed = sessions.perform(agent, id, new ActionRequest(ActionType.TYPE, null, Locator.testId("password"), null,
                    "demo-password", null, null, null, null, null, null, null, null));
            assertThat(typed.success()).as(typed.message()).isTrue();
            assertThat(sessions.perform(agent, id, ActionRequest.attribute(Locator.testId("password"), "value")).value()).isEqualTo("[REDACTED]");
            DomSnapshot full = sessions.snapshot(agent, id, SnapshotRequest.of(SnapshotMode.FULL));
            assertThat(full.elements()).noneMatch(e -> "demo-password".equals(e.value()));

            ActionResult enter = sessions.perform(agent, id, ActionRequest.press(Locator.testId("password"), "Enter"));
            assertThat(enter.success()).as(enter.message()).isTrue();
            assertThat(sessions.waitFor(agent, id, new WaitRequest(null, null, null, null, "**/dashboard", null, 10_000)).satisfied()).isTrue();
            assertThat(sessions.waitFor(agent, id, new WaitRequest(null, null, null, null, null, "1284", 10_000)).satisfied()).isTrue();

            // Console: runtime error with source-mapped location; network: deliberate 500.
            long deadline = System.currentTimeMillis() + 10_000;
            EventPage<ConsoleEntry> errors;
            do {
                errors = sessions.console(agent, id, ConsoleQuery.minLevel(ConsoleLevel.ERROR));
                Thread.sleep(150);
            } while (errors.items().stream().noneMatch(e -> e.kind() == ConsoleKind.PAGE_ERROR && e.originalSource() != null)
                    && System.currentTimeMillis() < deadline);
            ConsoleEntry runtime = errors.items().stream().filter(e -> e.kind() == ConsoleKind.PAGE_ERROR).findFirst().orElseThrow();
            assertThat(runtime.message()).contains("forEach");
            assertThat(runtime.originalSource().file()).isEqualTo("src/dashboard.ts");
            assertThat(runtime.originalSource().line()).isEqualTo(27);
            assertThat(sessions.network(agent, id, NetworkQuery.failed()).items())
                    .anyMatch(e -> e.url().endsWith("/api/notifications") && Integer.valueOf(500).equals(e.status()));
            assertThat(sessions.network(agent, id, new NetworkQuery(null, true, null, null, null, "/api/stats", null, null, null))
                    .items().get(0).requestHeaders().get("authorization")).isEqualTo("[REDACTED]");

            // Inspect, accessibility, select, check, screenshot, preview, history.
            assertThat(sessions.inspect(agent, id, new InspectRequest(null, Locator.role("heading", "Dashboard"), null)).tag()).isEqualTo("h1");
            assertThat(sessions.accessibility(agent, id, null, null).ariaSnapshot()).contains("heading \"Dashboard\"");
            sessions.navigate(agent, id, new NavigateRequest("/register"));
            ActionResult select = sessions.perform(agent, id, ActionRequest.select(Locator.label("Country"), List.of("Germany")));
            assertThat(select.value()).isEqualTo(List.of("de"));
            assertThat(sessions.perform(agent, id, ActionRequest.of(ActionType.CHECK, Locator.css("#terms"))).success()).isTrue();
            assertThat(sessions.perform(agent, id, ActionRequest.of(ActionType.IS_VISIBLE, Locator.text("Create account"))).value())
                    .isEqualTo(true);
            ScreenshotInfo shot = sessions.screenshot(agent, id, new ScreenshotRequest(null, "ext", true, null, ImageFormat.PNG, null, null, true, false));
            assertThat(shot.width()).isGreaterThan(100);
            assertThat(sessions.preview(agent, id, null, 50)[0]).isEqualTo((byte) 0xFF);
            assertThat(sessions.back(agent, id, null).url()).contains("/dashboard");

            ActionResult missing = sessions.perform(agent, id, ActionRequest.of(ActionType.CLICK, Locator.testId("nope")).withTimeout(500));
            assertThat(missing.errorCode()).isEqualTo("ELEMENT_NOT_FOUND");
            ActionResult ambiguous = sessions.perform(agent, id, ActionRequest.of(ActionType.CLICK, Locator.css("a")).withTimeout(500));
            assertThat(ambiguous.errorCode()).isEqualTo("VALIDATION_FAILED");

            // Single-tab sessions.
            assertThatThrownBy(() -> sessions.openTab(agent, id, new OpenTabRequest(null)))
                    .isInstanceOf(BridgeException.class).extracting("code").isEqualTo(ErrorCode.UNSUPPORTED);

            sessions.close(admin, id);
            assertThat(sessions.list(admin, null)).isEmpty();
        }
    }

    @Test
    void readsAreRefusedWhileTheUserIsOnAnotherSite() throws Exception {
        try (FakeChromeExtension ext = new FakeChromeExtension(hub)) {
            ext.share("about:blank", false);
            String id = awaitSession();
            assertThat(sessions.list(agent, null)).isEmpty(); // the user did not let agents use this tab
            sessions.navigate(admin, id, new NavigateRequest(app.url + "/login"));
            assertThat(sessions.snapshot(admin, id, SnapshotRequest.of(SnapshotMode.COMPACT)).pageTitle()).isEqualTo("Login");

            // The user browses to a page that is not a development host.
            ext.userNavigate("data:text/html,<title>Private</title><h1>Private mail</h1><script>console.error('secret stuff')</script>");
            long deadline = System.currentTimeMillis() + 10_000;
            while (!sessions.tabs(admin, id).get(0).url().startsWith("data:") && System.currentTimeMillis() < deadline) Thread.sleep(100);
            Thread.sleep(500);
            assertThatThrownBy(() -> sessions.snapshot(admin, id, SnapshotRequest.of(SnapshotMode.COMPACT)))
                    .extracting("code").isEqualTo(ErrorCode.URL_NOT_ALLOWED);
            assertThatThrownBy(() -> sessions.screenshot(admin, id, ScreenshotRequest.viewport("x")))
                    .extracting("code").isEqualTo(ErrorCode.URL_NOT_ALLOWED);
            assertThat(sessions.page(admin, id, null).title()).isEmpty();
            assertThat(sessions.console(admin, id, ConsoleQuery.all()).items()).noneMatch(e -> e.message().contains("secret"));
            sessions.close(admin, id);
        }
    }

    @Test
    void extensionDisconnectClosesItsSessions() throws Exception {
        FakeChromeExtension ext = new FakeChromeExtension(hub);
        ext.share("about:blank", true);
        String id = awaitSession();
        ext.close();
        long deadline = System.currentTimeMillis() + 10_000;
        while (sessions.canAccess(admin, id) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertThat(sessions.canAccess(admin, id)).isFalse();
    }
}
