package dev.cdb.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cdb.client.BrowserDriverClient;
import dev.cdb.driver.DriverApplication;
import dev.cdb.driver.security.TokenService;
import dev.cdb.mcp.BrowserTools;
import dev.cdb.mcp.McpServer;
import dev.cdb.protocol.Json;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance criteria 11 and 12: an MCP client drives the browser through the MCP server and validates the sample
 * login workflow. The MCP server runs in-process over piped stdio, exactly as a coding agent would use it.
 */
@SpringBootTest(classes = DriverApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cdb.data-dir=target/it-data-mcp"})
class McpEndToEndTest {
    static SampleApp app;
    final ObjectMapper m = Json.mapper();
    final AtomicInteger ids = new AtomicInteger();

    @LocalServerPort
    int port;
    @Autowired
    TokenService tokens;

    @BeforeAll
    static void startApp() throws Exception {
        app = SampleApp.start();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) app.close();
    }

    final class Client implements AutoCloseable {
        final PipedOutputStream toServer = new PipedOutputStream();
        final PrintStream writer = new PrintStream(toServer, true, StandardCharsets.UTF_8);
        final BufferedReader reader;
        final Thread thread;

        Client() throws Exception {
            PipedInputStream serverIn = new PipedInputStream(toServer, 1 << 20);
            PipedInputStream clientIn = new PipedInputStream(1 << 22);
            PipedOutputStream serverOut = new PipedOutputStream(clientIn);
            reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));
            BrowserTools tools = new BrowserTools(() -> new BrowserDriverClient("http://127.0.0.1:" + port, tokens.agentToken(), "mcp-it"));
            McpServer server = new McpServer(tools, serverIn, new PrintStream(serverOut, true, StandardCharsets.UTF_8));
            thread = new Thread(() -> {
                try {
                    server.run();
                } catch (Exception ignored) {
                    // closed
                }
            });
            thread.start();
        }

        JsonNode request(String method, Object params) throws Exception {
            int id = ids.incrementAndGet();
            var msg = m.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
            msg.set("params", m.valueToTree(params));
            writer.println(m.writeValueAsString(msg));
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode n = m.readTree(line);
                if (n.path("id").asInt() == id) return n;
            }
            throw new IllegalStateException("server closed");
        }

        JsonNode tool(String name, Object args) throws Exception {
            JsonNode r = request("tools/call", java.util.Map.of("name", name, "arguments", args));
            assertThat(r.has("result")).as(r.toString()).isTrue();
            return r.get("result");
        }

        @Override
        public void close() throws Exception {
            writer.close();
            thread.join(10_000);
        }
    }

    @Test
    void agentValidatesLoginWorkflowThroughMcp() throws Exception {
        try (Client c = new Client()) {
            JsonNode init = c.request("initialize", java.util.Map.of("protocolVersion", "2025-11-25",
                    "capabilities", java.util.Map.of(), "clientInfo", java.util.Map.of("name", "claude-code", "version", "test")));
            assertThat(init.at("/result/protocolVersion").asText()).isEqualTo("2025-11-25");
            assertThat(c.request("tools/list", java.util.Map.of()).at("/result/tools").size()).isEqualTo(17);

            JsonNode created = c.tool("browser_create_session", java.util.Map.of("url", app.url + "/login"));
            assertThat(created.get("isError").asBoolean()).as(created.toString()).isFalse();
            String sessionId = created.at("/structuredContent/sessionId").asText();

            JsonNode snap = c.tool("browser_snapshot", java.util.Map.of("sessionId", sessionId, "mode", "interactive"));
            String text = snap.at("/content/0/text").asText();
            assertThat(text).contains("textbox \"Email\"").contains("button \"Sign In\"");
            String emailRef = null;
            for (JsonNode e : snap.at("/structuredContent/elements")) {
                if ("Email".equals(e.path("name").asText())) emailRef = e.get("reference").asText();
            }
            assertThat(emailRef).isNotNull();

            assertThat(c.tool("browser_type", java.util.Map.of("sessionId", sessionId, "ref", emailRef, "text", "qa@example.test"))
                    .get("isError").asBoolean()).isFalse();
            assertThat(c.tool("browser_type", java.util.Map.of("sessionId", sessionId, "locator", java.util.Map.of("testId", "password"),
                    "text", "demo-password", "pressKey", "Enter")).get("isError").asBoolean()).isFalse();
            JsonNode waited = c.tool("browser_wait", java.util.Map.of("sessionId", sessionId, "text", "1284", "timeoutMs", 10000));
            assertThat(waited.at("/structuredContent/satisfied").asBoolean()).as(waited.toString()).isTrue();

            JsonNode console = c.tool("browser_console_logs", java.util.Map.of("sessionId", sessionId, "level", "ERROR"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (!console.at("/content/0/text").asText().contains("src/dashboard.ts:27") && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
                console = c.tool("browser_console_logs", java.util.Map.of("sessionId", sessionId, "level", "ERROR"));
            }
            assertThat(console.at("/content/0/text").asText()).contains("src/dashboard.ts:27").contains("<untrusted-page-content>");

            JsonNode network = c.tool("browser_network_logs", java.util.Map.of("sessionId", sessionId, "failedOnly", true));
            assertThat(network.at("/content/0/text").asText()).contains("/api/notifications -> 500");

            JsonNode shot = c.tool("browser_screenshot", java.util.Map.of("sessionId", sessionId));
            assertThat(shot.at("/content/1/type").asText()).isEqualTo("image");
            String shotId = shot.at("/structuredContent/screenshotId").asText();
            assertThat(c.tool("browser_compare_screenshots", java.util.Map.of("candidateScreenshotId", shotId,
                    "baselineScreenshotId", shotId)).at("/structuredContent/match").asBoolean()).isTrue();

            JsonNode blocked = c.tool("browser_navigate", java.util.Map.of("sessionId", sessionId, "url", "https://example.com"));
            assertThat(blocked.get("isError").asBoolean()).isTrue();
            assertThat(blocked.at("/content/0/text").asText()).contains("URL_NOT_ALLOWED");

            JsonNode injection = c.tool("browser_navigate", java.util.Map.of("sessionId", sessionId, "url", app.url + "/injection"));
            assertThat(injection.get("isError").asBoolean()).isFalse();
            JsonNode injSnap = c.tool("browser_snapshot", java.util.Map.of("sessionId", sessionId, "mode", "compact"));
            assertThat(injSnap.at("/content/0/text").asText()).startsWith("WARNING");
            assertThat(injSnap.at("/structuredContent/promptInjectionWarnings").size()).isGreaterThan(0);

            JsonNode flow = c.tool("browser_test_flow", java.util.Map.of("scenario", m.readTree("""
                    {"name":"Login page validation","baseUrl":"%s","steps":[
                      {"action":"navigate","path":"/login"},
                      {"action":"fill","locator":{"role":"textbox","name":"Email"},"value":"${TEST_EMAIL}"},
                      {"action":"fill","locator":{"testId":"password"},"value":"${TEST_PASSWORD}"},
                      {"action":"click","locator":{"role":"button","name":"Sign In"}},
                      {"action":"assertVisible","text":"Dashboard"},
                      {"action":"screenshot","name":"dashboard-after-login"}]}
                    """.formatted(app.url))));
            assertThat(flow.at("/structuredContent/success").asBoolean()).as(flow.toString()).isTrue();
            assertThat(flow.at("/content/0/text").asText()).contains("PASSED").doesNotContain("demo-password");

            assertThat(c.tool("browser_close_session", java.util.Map.of("sessionId", sessionId)).get("isError").asBoolean()).isFalse();
            assertThat(c.tool("browser_list_sessions", java.util.Map.of()).at("/structuredContent/sessions").size()).isZero();
        }
    }
}
