package dev.cdb.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolTest {

    @Test
    void stateMachineAllowsOnlyDocumentedTransitions() {
        assertThat(SessionState.CREATED.canTransitionTo(SessionState.STARTING)).isTrue();
        assertThat(SessionState.RUNNING.canTransitionTo(SessionState.DISCONNECTED)).isTrue();
        assertThat(SessionState.DISCONNECTED.canTransitionTo(SessionState.STARTING)).isTrue();
        assertThat(SessionState.STOPPED.canTransitionTo(SessionState.STARTING)).isFalse();
        assertThat(SessionState.CREATED.canTransitionTo(SessionState.RUNNING)).isFalse();
        assertThat(SessionState.STOPPING.canTransitionTo(SessionState.RUNNING)).isFalse();
    }

    @Test
    void viewportValidatesBounds() {
        assertThat(ViewportPreset.MOBILE.viewport()).isEqualTo(new Viewport(390, 844));
        assertThatThrownBy(() -> new Viewport(10, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ViewportRequest(null, 800, null).resolve()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventTypePatterns() {
        assertThat(EventTypes.matches("browser.console.*", EventTypes.CONSOLE_ERROR)).isTrue();
        assertThat(EventTypes.matches("browser.console.*", EventTypes.NETWORK_FAILED)).isFalse();
        assertThat(EventTypes.matches("*", EventTypes.TAB_CLOSED)).isTrue();
        assertThat(EventTypes.matches(EventTypes.TAB_CLOSED, EventTypes.TAB_CLOSED)).isTrue();
    }

    @Test
    void locatorDescribesWithoutValues() {
        assertThat(Locator.role("button", "Sign In").describe()).isEqualTo("role=button[name=\"Sign In\"]");
        assertThat(Locator.css("#x").isEmpty()).isFalse();
        assertThat(new Locator(null, null, null, null, null, null, null, null, null, null, null).isEmpty()).isTrue();
    }

    @Test
    void sourceLocationFileName() {
        assertThat(SourceLocation.fileName("http://localhost:3000/static/js/app.js?v=3#x")).isEqualTo("app.js");
        assertThat(SourceLocation.fileName(null)).isNull();
    }

    @Test
    void jsonRoundTrip() throws Exception {
        var req = ActionRequest.select(Locator.testId("country"), List.of("NL"));
        String json = Json.mapper().writeValueAsString(req);
        assertThat(json).doesNotContain("\"ref\"");
        assertThat(Json.mapper().readValue(json, ActionRequest.class)).isEqualTo(req);
        assertThat(ConsoleLevel.fromConsoleType("warning")).isEqualTo(ConsoleLevel.WARNING);
    }
}
