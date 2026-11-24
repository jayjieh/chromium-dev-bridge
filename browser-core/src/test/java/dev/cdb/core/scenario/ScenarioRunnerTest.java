package dev.cdb.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cdb.core.CoreSettings;
import dev.cdb.core.DriverMetrics;
import dev.cdb.core.FakeProvider;
import dev.cdb.core.AuditSink;
import dev.cdb.core.Principal;
import dev.cdb.core.events.EventBus;
import dev.cdb.core.session.SessionManager;
import dev.cdb.protocol.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioRunnerTest {
    @TempDir
    Path dir;
    SessionManager manager;
    FakeProvider provider;
    final Principal admin = Principal.admin();

    @BeforeEach
    void setUp() {
        provider = new FakeProvider();
        manager = new SessionManager(CoreSettings.defaults(dir), provider, new EventBus(), DriverMetrics.NOOP, AuditSink.NOOP, null);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    static ScenarioStep step(String action) {
        return new ScenarioStep(action, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void runsLoginFlowAndMasksSecrets() {
        String id = manager.create(admin, null).sessionId();
        List<ScenarioStep> steps = List.of(
                new ScenarioStep("navigate", null, "/login", null, null, null, null, null, null, null, null, null, null, null, null),
                new ScenarioStep("fill", null, null, null, Locator.role("textbox", "Email"), null, "${TEST_EMAIL}", null, null, null, null, null, null, null, null),
                new ScenarioStep("fill", null, null, null, Locator.testId("password"), null, "${TEST_PASSWORD}", null, null, null, null, null, null, null, null),
                new ScenarioStep("click", null, null, null, Locator.role("button", "Sign In"), null, null, null, null, null, null, null, null, null, null),
                new ScenarioStep("assertVisible", null, null, null, null, "Dashboard", null, null, null, null, null, null, null, null, null),
                new ScenarioStep("assertUrl", null, null, null, null, null, null, null, null, null, "/dashboard", null, null, null, null),
                new ScenarioStep("assertText", "echo ${TEST_PASSWORD}", null, null, Locator.css("h1"), "Welcome", null, null, null, null, null, null, null, null, null),
                step("assertNoConsoleErrors"),
                new ScenarioStep("screenshot", null, null, null, null, null, null, null, null, "dashboard-after-login", null, null, null, null, null));
        Scenario sc = new Scenario("Login page validation", "http://localhost:3000", null, null, null, null, null, steps);
        ScenarioRunner runner = new ScenarioRunner(manager, Map.of("TEST_EMAIL", "qa@example.test", "TEST_PASSWORD", "S3cret-pass", "HOME", "/root")::get);
        ScenarioReport report = runner.run(admin, id, sc);
        assertThat(report.steps()).allSatisfy(s -> assertThat(s.success()).as(s.action() + ": " + s.message()).isTrue());
        assertThat(report.success()).isTrue();
        assertThat(report.passed()).isEqualTo(9);
        assertThat(report.toString()).doesNotContain("S3cret-pass").doesNotContain("eyJhbGci");
        List<ActionRequest> actions = provider.drivers.get(0).actions;
        assertThat(actions.get(1).value()).isEqualTo("S3cret-pass");
    }

    @Test
    void stopsAtFirstFailureWithScreenshotAndSkipsRest() {
        String id = manager.create(admin, null).sessionId();
        Scenario sc = new Scenario("broken", "http://localhost:3000", null, null, null, null, true, List.of(
                new ScenarioStep("navigate", null, "/login", null, null, null, null, null, null, null, null, null, null, null, null),
                new ScenarioStep("click", null, null, null, Locator.testId("missing"), null, null, null, null, null, null, null, null, null, null),
                step("assertNoConsoleErrors")));
        ScenarioReport report = new ScenarioRunner(manager, k -> null).run(admin, id, sc);
        assertThat(report.success()).isFalse();
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.steps().get(1).screenshotId()).isNotNull();
        assertThat(report.steps().get(1).message()).contains("missing");
    }

    @Test
    void onlyTestPrefixedEnvironmentVariablesAreVisible() {
        VariableResolver r = new VariableResolver(null, Map.of("A", "1"), Map.of("TEST_X", "x", "AWS_SECRET", "nope")::get);
        assertThat(r.resolve("${A}-${TEST_X}")).isEqualTo("1-x");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> r.resolve("${AWS_SECRET}"))
                .hasMessageContaining("Unresolved");
    }

    @Test
    void fixturesOverrideVariables() {
        VariableResolver r = new VariableResolver(Map.of("USER", "fixture-user"), Map.of("USER", "var-user"), k -> null);
        assertThat(r.resolve("${USER}")).isEqualTo("fixture-user");
        assertThat(r.mask("hello fixture-user")).isEqualTo("hello ***");
    }
}
