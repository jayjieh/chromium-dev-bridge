package dev.cdb.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cdb.core.events.EventBus;
import dev.cdb.core.session.SessionManager;
import dev.cdb.protocol.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionManagerTest {
    @TempDir
    Path dir;
    FakeProvider provider;
    EventBus bus;
    SessionManager manager;
    List<DriverEvent> events;
    AtomicReference<Instant> now;
    List<String> audit;
    final Principal admin = Principal.admin();
    final Principal agent = Principal.agent("claude-code");
    final Principal otherAgent = Principal.agent("codex");

    @BeforeEach
    void setUp() {
        provider = new FakeProvider();
        bus = new EventBus();
        events = new CopyOnWriteArrayList<>();
        bus.subscribe(events::add);
        now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        audit = new CopyOnWriteArrayList<>();
        CoreSettings settings = CoreSettings.defaults(dir).withMaxSessions(3).withAllowEvaluate(true);
        manager = new SessionManager(settings, provider, bus, DriverMetrics.NOOP,
                (p, op, sid, outcome, details) -> audit.add(p.name() + " " + op + " " + outcome + " " + details), now::get);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void createsRunningSessionAndPublishesEvents() {
        SessionInfo info = manager.create(agent, SessionCreateRequest.builder().projectId("demo").build());
        assertThat(info.state()).isEqualTo(SessionState.RUNNING);
        assertThat(info.sessionId()).startsWith("session-");
        assertThat(info.owner()).isEqualTo("agent:claude-code");
        assertThat(info.provider().id()).isEqualTo("fake");
        assertThat(events).extracting(DriverEvent::type).contains(EventTypes.SESSION_CREATED, EventTypes.SESSION_STATE);
    }

    @Test
    void agentsAreIsolatedFromEachOther() {
        SessionInfo mine = manager.create(agent, null);
        assertThatThrownBy(() -> manager.get(otherAgent, mine.sessionId()))
                .isInstanceOf(BridgeException.class).extracting("code").isEqualTo(ErrorCode.SESSION_NOT_FOUND);
        assertThat(manager.list(otherAgent, null)).isEmpty();
        assertThat(manager.list(admin, null)).hasSize(1);
    }

    @Test
    void adminCanShareSessionWithAgents() {
        SessionInfo shared = manager.create(admin, SessionCreateRequest.builder().agentAccess(true).build());
        SessionInfo privateOne = manager.create(admin, null);
        assertThat(manager.list(agent, null)).extracting(SessionInfo::sessionId).containsExactly(shared.sessionId());
        assertThatThrownBy(() -> manager.get(agent, privateOne.sessionId())).isInstanceOf(BridgeException.class);
    }

    @Test
    void agentCannotSelfGrantAgentAccessOrApprovals() {
        SessionInfo s = manager.create(agent, SessionCreateRequest.builder().agentAccess(true).build());
        assertThat(s.agentAccess()).isFalse();
        assertThatThrownBy(() -> manager.approve(agent, s.sessionId(), new ApprovalRequest(ApprovalKind.ORIGIN, "https://example.com", null)))
                .isInstanceOf(BridgeException.class).extracting("code").isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    void enforcesMaxSessions() {
        manager.create(admin, null);
        manager.create(admin, null);
        manager.create(admin, null);
        assertThatThrownBy(() -> manager.create(admin, null)).extracting("code").isEqualTo(ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void navigationToNonDevelopmentHostNeedsApproval() {
        SessionInfo s = manager.create(admin, null);
        assertThat(manager.navigate(admin, s.sessionId(), new NavigateRequest("http://localhost:3000/login")).status()).isEqualTo(200);
        assertThatThrownBy(() -> manager.navigate(admin, s.sessionId(), new NavigateRequest("https://example.com/")))
                .extracting("code").isEqualTo(ErrorCode.URL_NOT_ALLOWED);
        assertThatThrownBy(() -> manager.navigate(admin, s.sessionId(), new NavigateRequest("file:///etc/passwd")))
                .extracting("code").isEqualTo(ErrorCode.URL_NOT_ALLOWED);
        manager.approve(admin, s.sessionId(), new ApprovalRequest(ApprovalKind.ORIGIN, "https://example.com/some/path", "test"));
        assertThat(manager.navigate(admin, s.sessionId(), new NavigateRequest("https://example.com/")).url()).isEqualTo("https://example.com/");
    }

    @Test
    void relativeUrlsResolveAgainstCurrentPage() {
        SessionInfo s = manager.create(admin, null);
        manager.navigate(admin, s.sessionId(), new NavigateRequest("http://localhost:3000/login"));
        assertThat(manager.navigate(admin, s.sessionId(), new NavigateRequest("/dashboard")).url())
                .isEqualTo("http://localhost:3000/dashboard");
    }

    @Test
    void mutatingActionsOnApprovedExternalOriginNeedSensitiveApproval() {
        SessionInfo s = manager.create(admin, null);
        manager.approve(admin, s.sessionId(), new ApprovalRequest(ApprovalKind.ORIGIN, "https://shop.example.com", null));
        manager.navigate(admin, s.sessionId(), new NavigateRequest("https://shop.example.com/cart"));
        assertThatThrownBy(() -> manager.perform(admin, s.sessionId(), ActionRequest.of(ActionType.CLICK, Locator.text("Buy"))))
                .extracting("code").isEqualTo(ErrorCode.APPROVAL_REQUIRED);
        assertThat(manager.perform(admin, s.sessionId(), ActionRequest.of(ActionType.GET_TEXT, Locator.css("h1"))).success()).isTrue();
        manager.approve(admin, s.sessionId(), new ApprovalRequest(ApprovalKind.SENSITIVE_ACTIONS, null, null));
        assertThat(manager.perform(admin, s.sessionId(), ActionRequest.of(ActionType.CLICK, Locator.text("Buy"))).success()).isTrue();
    }

    @Test
    void actionResultsAreRedactedAndAuditDoesNotContainValues() {
        SessionInfo s = manager.create(admin, null);
        manager.perform(admin, s.sessionId(), ActionRequest.fill(Locator.label("Password"), "hunter2-secret"));
        ActionResult text = manager.perform(admin, s.sessionId(), ActionRequest.of(ActionType.GET_TEXT, Locator.css("p")));
        assertThat((String) text.value()).doesNotContain("eyJhbGci").contains("[REDACTED]");
        assertThat(String.join("\n", audit)).doesNotContain("hunter2-secret").contains("valueLength=14");
    }

    @Test
    void validatesActions() {
        SessionInfo s = manager.create(admin, null);
        assertThatThrownBy(() -> manager.perform(admin, s.sessionId(), ActionRequest.of(ActionType.CLICK, null)))
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> manager.perform(admin, s.sessionId(), ActionRequest.fill(Locator.css("#a"), null)))
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(manager.perform(admin, s.sessionId(), ActionRequest.of(ActionType.CLICK, Locator.testId("missing"))).errorCode())
                .isEqualTo("ELEMENT_NOT_FOUND");
    }

    @Test
    void capturesRedactedConsoleAndNetwork() {
        SessionInfo s = manager.create(admin, null);
        FakeProvider.FakeDriver d = provider.drivers.get(0);
        d.listener.onConsole(new ConsoleEntry(0, "e1", null, "tab-1", Instant.now(), ConsoleLevel.ERROR, ConsoleKind.CONSOLE,
                "Failed with Authorization: Bearer abcdefghijklmnop", SourceLocation.ofUrl("http://localhost:3000/app.js", 4, 2), null, List.of()));
        d.listener.onConsole(new ConsoleEntry(0, "e2", null, "tab-1", Instant.now(), ConsoleLevel.INFO, ConsoleKind.CONSOLE,
                "hello", null, null, List.of()));
        d.listener.onNetwork(new NetworkEntry(0, "r1", null, "tab-1", "GET", "http://localhost:3000/api/data?token=abc&x=1",
                "fetch", NetworkState.PENDING, null, null, null, Instant.now(), null, null, null, false, false,
                Map.of("authorization", "Bearer xyz", "accept", "json"), null, null, null));
        d.listener.onNetwork(new NetworkEntry(0, "r1", null, "tab-1", "GET", "http://localhost:3000/api/data?token=abc&x=1",
                "fetch", NetworkState.COMPLETED, 500, "Server Error", "application/json", Instant.now(), 12.0, null, null, false, false,
                Map.of("authorization", "Bearer xyz"), Map.of("set-cookie", "sid=1"), null, "{\"password\":\"p4ss\",\"ok\":false}"));

        EventPage<ConsoleEntry> errors = manager.console(admin, s.sessionId(), ConsoleQuery.minLevel(ConsoleLevel.ERROR));
        assertThat(errors.items()).hasSize(1);
        assertThat(errors.items().get(0).message()).doesNotContain("abcdefghijklmnop");
        assertThat(manager.console(admin, s.sessionId(), ConsoleQuery.all()).items()).hasSize(2);

        EventPage<NetworkEntry> net = manager.network(admin, s.sessionId(), NetworkQuery.failed());
        assertThat(net.items()).hasSize(1);
        NetworkEntry e = net.items().get(0);
        assertThat(e.url()).contains("token=[REDACTED]").contains("x=1");
        assertThat(e.requestHeaders().get("authorization")).isEqualTo("[REDACTED]");
        assertThat(e.responseHeaders().get("set-cookie")).isEqualTo("[REDACTED]");
        assertThat(e.responseBody()).doesNotContain("p4ss");
        assertThat(events).extracting(DriverEvent::type).contains(EventTypes.CONSOLE_ERROR, EventTypes.NETWORK_RESPONSE);
    }

    @Test
    void closeReleasesDriverAndRemovesSession() {
        SessionInfo s = manager.create(admin, null);
        manager.close(admin, s.sessionId());
        assertThat(provider.drivers.get(0).closed).isTrue();
        assertThat(manager.list(admin, null)).isEmpty();
        assertThat(events).extracting(DriverEvent::type).contains(EventTypes.SESSION_CLOSED);
    }

    @Test
    void idleSessionsExpire() {
        SessionInfo s = manager.create(admin, SessionCreateRequest.builder().idleTimeoutSeconds(60).build());
        now.set(now.get().plus(Duration.ofSeconds(30)));
        manager.maintain();
        assertThat(manager.list(admin, null)).hasSize(1);
        now.set(now.get().plus(Duration.ofSeconds(61)));
        manager.maintain();
        assertThat(manager.list(admin, null)).isEmpty();
        assertThat(provider.drivers.get(0).closed).isTrue();
        assertThat(s).isNotNull();
    }

    @Test
    void failedStartIsReportedAndRestartRecovers() {
        provider.failNextOpen = true;
        assertThatThrownBy(() -> manager.create(admin, null)).extracting("code").isEqualTo(ErrorCode.BROWSER_ERROR);
        SessionInfo failed = manager.list(admin, null).get(0);
        assertThat(failed.state()).isEqualTo(SessionState.FAILED);
        assertThat(failed.failureReason()).contains("boom");
        SessionInfo restarted = manager.restart(admin, failed.sessionId());
        assertThat(restarted.state()).isEqualTo(SessionState.RUNNING);
    }

    @Test
    void screenshotsCompareAndBaselines() {
        SessionInfo s = manager.create(admin, null);
        ScreenshotInfo a = manager.screenshot(admin, s.sessionId(), ScreenshotRequest.viewport("a"));
        provider.drivers.get(0).screenshotColor = java.awt.Color.BLACK;
        ScreenshotInfo b = manager.screenshot(admin, s.sessionId(), ScreenshotRequest.viewport("b"));
        assertThat(a.width()).isEqualTo(40);
        CompareResult same = manager.compare(admin, new CompareRequest(a.screenshotId(), a.screenshotId(), null, null, null));
        assertThat(same.match()).isTrue();
        CompareResult diff = manager.compare(admin, new CompareRequest(b.screenshotId(), a.screenshotId(), null, null, null));
        assertThat(diff.match()).isFalse();
        assertThat(diff.diffRatio()).isEqualTo(1.0);
        assertThat(diff.diffScreenshotId()).isNotNull();
        manager.saveBaseline(admin, new BaselineRequest("home", a.screenshotId()));
        assertThat(manager.baselines(admin)).extracting(BaselineInfo::name).contains("home");
        assertThat(manager.compare(admin, new CompareRequest(a.screenshotId(), null, "home", null, null)).match()).isTrue();
        assertThat(manager.screenshots(admin, s.sessionId())).hasSizeGreaterThanOrEqualTo(3);
        assertThatThrownBy(() -> manager.screenshotImage(otherAgent, a.screenshotId())).isInstanceOf(BridgeException.class);
    }

    @Test
    void evaluateNeedsAdminAndApproval() {
        SessionInfo s = manager.create(admin, SessionCreateRequest.builder().agentAccess(true).build());
        assertThatThrownBy(() -> manager.evaluate(agent, s.sessionId(), new EvaluateRequest(null, "1+1")))
                .extracting("code").isEqualTo(ErrorCode.ACCESS_DENIED);
        assertThatThrownBy(() -> manager.evaluate(admin, s.sessionId(), new EvaluateRequest(null, "1+1")))
                .extracting("code").isEqualTo(ErrorCode.APPROVAL_REQUIRED);
        manager.approve(admin, s.sessionId(), new ApprovalRequest(ApprovalKind.EVALUATE, null, null));
        assertThat(manager.evaluate(admin, s.sessionId(), new EvaluateRequest(null, "1+1")).value()).isEqualTo(42);
    }

    @Test
    void validatesCreateRequest() {
        assertThatThrownBy(() -> manager.create(admin, SessionCreateRequest.builder().profileName("../etc").build()))
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> manager.create(admin, SessionCreateRequest.builder().connectEndpoint("http://10.0.0.5:9222").build()))
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> manager.create(agent, SessionCreateRequest.builder().connectEndpoint("http://127.0.0.1:9222").build()))
                .extracting("code").isEqualTo(ErrorCode.ACCESS_DENIED);
        assertThatThrownBy(() -> manager.create(admin, SessionCreateRequest.builder().initialUrl("https://evil.example").build()))
                .extracting("code").isEqualTo(ErrorCode.URL_NOT_ALLOWED);
    }

    @Test
    void disconnectMovesToDisconnectedThenAutoRestarts() throws Exception {
        SessionInfo s = manager.create(admin, null);
        FakeProvider.FakeDriver d = provider.drivers.get(0);
        d.connected = false;
        d.listener.onDisconnected("crashed");
        long deadline = System.currentTimeMillis() + 5000;
        while (d.restarts == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertThat(d.restarts).isEqualTo(1);
        while (manager.get(admin, s.sessionId()).state() != SessionState.RUNNING && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(manager.get(admin, s.sessionId()).state()).isEqualTo(SessionState.RUNNING);
        assertThat(events).anySatisfy(e -> assertThat(e.data().toString()).contains("DISCONNECTED"));
    }
}
