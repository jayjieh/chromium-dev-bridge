package dev.cdb.provider.extension;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cdb.core.spi.BrowserEventListener;
import dev.cdb.core.spi.SessionLaunchSpec;
import dev.cdb.core.spi.SessionOpener;
import dev.cdb.protocol.Json;
import dev.cdb.protocol.SessionIsolation;
import dev.cdb.protocol.Viewport;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Protocol-level test with a scripted fake extension (no browser). */
class ExtensionConnectionTest {
    final ObjectMapper m = Json.mapper();
    final List<JsonNode> sent = new CopyOnWriteArrayList<>();
    final List<String> attached = new CopyOnWriteArrayList<>();
    final List<String> detached = new CopyOnWriteArrayList<>();
    final List<String> approved = new CopyOnWriteArrayList<>();
    ExtensionHub hub;
    ExtensionHub.Transport transport;

    /** Fake extension: answers every cdp.send with canned results. */
    void reply(JsonNode msg) {
        if (!msg.has("id") || !"cdp.send".equals(msg.path("method").asText())) return;
        String method = msg.at("/params/method").asText();
        String result = switch (method) {
            case "Page.getFrameTree" -> "{\"frameTree\":{\"frame\":{\"id\":\"F1\",\"url\":\"http://localhost:3000/\"}}}";
            case "Runtime.evaluate" -> "{\"result\":{\"type\":\"object\",\"value\":{\"url\":\"http://localhost:3000/\",\"title\":\"Home\",\"readyState\":\"complete\"}}}";
            default -> "{}";
        };
        hub.received(transport, "{\"jsonrpc\":\"2.0\",\"id\":" + msg.get("id") + ",\"result\":" + result + "}");
    }

    @Test
    void shareCreatesSessionAndUnshareDetaches() throws Exception {
        SessionOpener[] captured = new SessionOpener[1];
        hub = new ExtensionHub(new ExtensionSessionRegistrar() {
            public String attach(ExtensionTab tab, SessionOpener opener) {
                captured[0] = opener;
                attached.add(tab.url());
                SessionLaunchSpec spec = new SessionLaunchSpec("s1", false, new Viewport(1280, 800), null, null, null,
                        SessionIsolation.SHARED, null, null, null, null, false, url -> url.startsWith("http://localhost"),
                        Duration.ofSeconds(2), Duration.ofSeconds(5), false, 0);
                opener.open(spec, BrowserEventListener.NOOP);
                return "session-abc";
            }
            public void detach(String sessionId) { detached.add(sessionId); }
            public void approveOrigin(String sessionId, String origin) { approved.add(origin); }
        }, Duration.ofSeconds(5), true);
        transport = new ExtensionHub.Transport() {
            public String id() { return "t1"; }
            public void send(String text) {
                try {
                    JsonNode msg = m.readTree(text);
                    sent.add(msg);
                    new Thread(() -> reply(msg)).start();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            public void close() { }
        };
        hub.connected(transport);
        hub.received(transport, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{\"version\":\"0.1.0\",\"userAgent\":\"Mozilla Chrome/142.0.1.2 Safari\"}}");
        hub.received(transport, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tab.shared\",\"params\":{\"tabId\":42,\"url\":\"https://staging.example.com/app\",\"title\":\"App\",\"approveOrigin\":true}}");

        long deadline = System.currentTimeMillis() + 5000;
        while (sent.stream().noneMatch(n -> n.path("id").asInt() == 2 && n.has("result")) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(sent).anyMatch(n -> n.path("id").asInt() == 2 && "session-abc".equals(n.at("/result/sessionId").asText()));
        assertThat(sent).anyMatch(n -> "session.attached".equals(n.path("method").asText()));
        assertThat(sent).anyMatch(n -> "Page.enable".equals(n.at("/params/method").asText()) && n.at("/params/tabId").asInt() == 42);
        assertThat(attached).containsExactly("https://staging.example.com/app");
        assertThat(approved).containsExactly("https://staging.example.com");
        assertThat(hub.status()).singleElement().satisfies(s -> {
            assertThat(s.browser()).isEqualTo("142.0.1.2");
            assertThat(s.sharedTabs()).containsExactly(42);
        });

        hub.received(transport, "{\"jsonrpc\":\"2.0\",\"method\":\"tab.unshared\",\"params\":{\"tabId\":42,\"reason\":\"canceled_by_user\"}}");
        while (detached.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(detached).containsExactly("session-abc");
        assertThat(hub.status().get(0).sharedTabs()).isEmpty();
        hub.close();
    }

    @Test
    void originParsing() {
        assertThat(ExtensionConnection.originOf("https://app.example.com:8443/x?y")).isEqualTo("https://app.example.com:8443");
        assertThat(ExtensionConnection.originOf("chrome://settings")).isNull();
        assertThat(ExtensionConnection.parseChromeVersion("x Chrome/159.0.8154.6 y")).isEqualTo("159.0.8154.6");
    }
}
