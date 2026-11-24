package dev.cdb.driver;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cdb.driver.security.TokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** HTTP-level tests of the driver API, security filter and WebSocket stream (fake browser provider). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cdb.data-dir=target/test-data/api", "cdb.security.rate-limit-per-second=1000", "cdb.security.rate-limit-burst=1000",
        "cdb.security.max-request-bytes=4096"})
@Import(FakeProviderConfig.class)
class ApiIntegrationTest {
    @LocalServerPort
    int port;
    @Autowired
    TokenService tokens;
    @Autowired
    ObjectMapper mapper;

    final HttpClient http = HttpClient.newHttpClient();

    String base() {
        return "http://127.0.0.1:" + port + "/api/v1";
    }

    HttpResponse<String> call(String method, String path, String token, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(HttpResponse<String> r) throws Exception {
        return mapper.readTree(r.body());
    }

    @Test
    void healthIsOpenEverythingElseNeedsToken() throws Exception {
        assertThat(call("GET", "/health", null, null).statusCode()).isEqualTo(200);
        HttpResponse<String> r = call("GET", "/sessions", null, null);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(json(r).get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(call("GET", "/sessions", "wrong-token", null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/sessions", tokens.adminToken(), null).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsBrowserOriginsAndForeignHosts() throws Exception {
        HttpResponse<String> origin = call("GET", "/sessions", tokens.adminToken(), null, "Origin", "http://localhost:3000");
        assertThat(origin.statusCode()).isEqualTo(403);
        assertThat(json(origin).get("detail").asText()).contains("Cross-origin");
        // java.net.http does not allow overriding Host; test the predicate directly.
        assertThat(new dev.cdb.driver.security.SecurityFilter(tokens, mapper, props(), null).hostAllowed("evil.com:7878")).isFalse();
    }

    @Autowired
    CdbProperties properties;

    CdbProperties props() {
        return properties;
    }

    @Test
    void rejectsOversizedBodies() throws Exception {
        HttpResponse<String> r = call("POST", "/sessions", tokens.adminToken(), "{\"label\":\"" + "x".repeat(5000) + "\"}");
        assertThat(r.statusCode()).isEqualTo(413);
    }

    @Test
    void sessionLifecycleOverHttp() throws Exception {
        String admin = tokens.adminToken();
        HttpResponse<String> created = call("POST", "/sessions", admin, "{\"projectId\":\"demo\",\"agentAccess\":true}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = json(created).get("sessionId").asText();
        assertThat(json(created).get("state").asText()).isEqualTo("RUNNING");

        HttpResponse<String> nav = call("POST", "/sessions/" + id + "/navigate", admin, "{\"url\":\"http://localhost:3000/login\"}");
        assertThat(nav.statusCode()).isEqualTo(200);
        assertThat(json(nav).get("title").asText()).isEqualTo("Login");

        HttpResponse<String> blocked = call("POST", "/sessions/" + id + "/navigate", admin, "{\"url\":\"https://example.com\"}");
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(json(blocked).get("code").asText()).isEqualTo("URL_NOT_ALLOWED");

        HttpResponse<String> dom = call("GET", "/sessions/" + id + "/dom?mode=INTERACTIVE", admin, null);
        assertThat(json(dom).get("elements").get(0).get("name").asText()).isEqualTo("Sign In");

        HttpResponse<String> action = call("POST", "/sessions/" + id + "/actions", admin,
                "{\"type\":\"CLICK\",\"locator\":{\"role\":\"button\",\"name\":\"Sign In\"}}");
        assertThat(json(action).get("success").asBoolean()).isTrue();
        assertThat(json(action).get("navigated").asBoolean()).isTrue();

        HttpResponse<String> bad = call("POST", "/sessions/" + id + "/actions", admin, "{\"type\":\"CLICK\"}");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(call("POST", "/sessions/" + id + "/actions", admin, "{not json").statusCode()).isEqualTo(400);

        HttpResponse<String> shot = call("POST", "/sessions/" + id + "/screenshots", admin, "{\"name\":\"home\"}");
        assertThat(shot.statusCode()).isEqualTo(201);
        String shotId = json(shot).get("screenshotId").asText();
        HttpResponse<byte[]> img = http.send(HttpRequest.newBuilder(URI.create(base() + "/screenshots/" + shotId + "/image"))
                .header("Authorization", "Bearer " + admin).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(img.statusCode()).isEqualTo(200);
        assertThat(img.headers().firstValue("Content-Type")).hasValue("image/png");

        // Agent token: sees the shared session, cannot grant approvals.
        String agent = tokens.agentToken();
        assertThat(json(call("GET", "/sessions", agent, null)).size()).isEqualTo(1);
        HttpResponse<String> approve = call("POST", "/sessions/" + id + "/approvals", agent,
                "{\"kind\":\"ORIGIN\",\"value\":\"https://example.com\"}");
        assertThat(approve.statusCode()).isEqualTo(403);
        assertThat(call("POST", "/sessions/" + id + "/approvals", admin, "{\"kind\":\"ORIGIN\",\"value\":\"https://example.com\"}")
                .statusCode()).isEqualTo(201);
        assertThat(call("POST", "/sessions/" + id + "/navigate", agent, "{\"url\":\"https://example.com\"}").statusCode()).isEqualTo(200);

        HttpResponse<String> scenario = call("POST", "/sessions/" + id + "/scenarios", agent, """
                {"name":"smoke","baseUrl":"http://localhost:3000","steps":[
                  {"action":"navigate","path":"/login"},
                  {"action":"click","locator":{"role":"button","name":"Sign In"}},
                  {"action":"assertVisible","text":"Dashboard"}]}
                """);
        assertThat(scenario.statusCode()).isEqualTo(200);
        assertThat(json(scenario).get("success").asBoolean()).as(scenario.body()).isTrue();

        HttpResponse<String> evaluate = call("POST", "/sessions/" + id + "/evaluate", admin, "{\"expression\":\"1+1\"}");
        assertThat(evaluate.statusCode()).isEqualTo(501);

        assertThat(call("DELETE", "/sessions/" + id, admin, null).statusCode()).isEqualTo(204);
        HttpResponse<String> gone = call("GET", "/sessions/" + id, admin, null);
        assertThat(gone.statusCode()).isEqualTo(404);
        assertThat(json(gone).get("code").asText()).isEqualTo("SESSION_NOT_FOUND");

        Path auditDir = Path.of("target/test-data/api/audit");
        assertThat(Files.list(auditDir).findFirst()).isPresent();
        String audit = Files.readString(Files.list(auditDir).findFirst().orElseThrow());
        assertThat(audit).contains("\"operation\":\"session.create\"").contains("approval.grant");
    }

    @Test
    void capabilitiesAndOpenApi() throws Exception {
        JsonNode caps = json(call("GET", "/capabilities", tokens.agentToken(), null));
        assertThat(caps.get("actionTypes").toString()).contains("CLICK");
        assertThat(caps.get("evaluateEnabled").asBoolean()).isFalse();
        HttpResponse<String> spec = call("GET", "/openapi.yaml", tokens.agentToken(), null);
        assertThat(spec.body()).startsWith("openapi: 3.1.0");
    }

    @Test
    void webSocketStreamsSubscribedEvents() throws Exception {
        List<String> messages = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> subscribed = new CompletableFuture<>();
        WebSocket ws = http.newWebSocketBuilder().header("Authorization", "Bearer " + tokens.adminToken())
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/events"), new WebSocket.Listener() {
                    final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            String m = buf.toString();
                            buf.setLength(0);
                            messages.add(m);
                            if (m.contains("subscriptionId")) subscribed.complete(null);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).get(10, TimeUnit.SECONDS);
        ws.sendText("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"subscribe\",\"params\":{\"events\":[\"browser.session.*\",\"browser.navigation.*\"]}}", true);
        subscribed.get(10, TimeUnit.SECONDS);
        ws.sendText("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", true);
        String id = json(call("POST", "/sessions", tokens.adminToken(), "{}")).get("sessionId").asText();
        call("POST", "/sessions/" + id + "/navigate", tokens.adminToken(), "{\"url\":\"http://localhost:3000/\"}");
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && messages.stream().noneMatch(m -> m.contains("browser.navigation.completed"))) {
            Thread.sleep(50);
        }
        String all = String.join("\n", messages);
        assertThat(all).contains("pong").contains("browser.session.created").contains("browser.navigation.completed")
                .contains("\"eventId\"").contains("\"timestamp\"").contains("\"sessionId\":\"" + id + "\"");
        assertThat(all).doesNotContain("browser.console");

        // Unauthenticated WebSocket handshake is rejected.
        CompletableFuture<WebSocket> anon = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/events"), new WebSocket.Listener() { });
        assertThat(anon).failsWithin(Duration.ofSeconds(10));
        ws.abort();
        call("DELETE", "/sessions/" + id, tokens.adminToken(), null);
    }
}
