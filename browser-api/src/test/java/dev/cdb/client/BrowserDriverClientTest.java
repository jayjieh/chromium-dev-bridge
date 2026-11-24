package dev.cdb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.cdb.protocol.NavigateRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserDriverClientTest {
    @Test
    void buildsQueryStrings() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("a", "x y");
        q.put("b", null);
        q.put("c", 3);
        assertThat(BrowserDriverClient.query(q)).isEqualTo("?a=x+y&c=3");
        assertThat(BrowserDriverClient.query(Map.of())).isEmpty();
    }

    @Test
    void sendsAuthHeadersAndParsesProblems() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> tool = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/sessions/s1/navigate", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            tool.set(ex.getRequestHeaders().getFirst("X-CDB-Tool"));
            byte[] b = "{\"code\":\"URL_NOT_ALLOWED\",\"detail\":\"Not a development host\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/problem+json");
            ex.sendResponseHeaders(403, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            BrowserDriverClient c = new BrowserDriverClient("http://127.0.0.1:" + server.getAddress().getPort(), "tok", "test")
                    .forTool("browser_navigate");
            assertThatThrownBy(() -> c.navigate("s1", new NavigateRequest("https://example.com")))
                    .isInstanceOf(DriverApiException.class)
                    .satisfies(e -> {
                        DriverApiException d = (DriverApiException) e;
                        assertThat(d.status()).isEqualTo(403);
                        assertThat(d.code()).isEqualTo("URL_NOT_ALLOWED");
                        assertThat(d.getMessage()).isEqualTo("Not a development host");
                    });
            assertThat(auth.get()).isEqualTo("Bearer tok");
            assertThat(tool.get()).isEqualTo("browser_navigate");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsConnectionFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("driver.json");
        assertThat(ConnectionFiles.read(f)).isEmpty();
        Files.writeString(f, "{\"baseUrl\":\"http://127.0.0.1:7878\",\"port\":7878,\"adminToken\":\"a\",\"agentToken\":\"b\",\"pid\":1}");
        assertThat(ConnectionFiles.read(f)).hasValueSatisfying(i -> assertThat(i.agentToken()).isEqualTo("b"));
        assertThatThrownBy(() -> BrowserDriverClient.fromConnectionFile(dir.resolve("missing.json"), true, "x"))
                .hasMessageContaining("not found");
    }
}
