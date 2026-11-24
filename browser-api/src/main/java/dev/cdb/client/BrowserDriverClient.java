package dev.cdb.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cdb.protocol.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Typed client for the Chromium Developer Bridge driver API. Thread-safe.
 *
 * <pre>
 * BrowserDriverClient client = BrowserDriverClient.fromConnectionFile(ConnectionFiles.defaultPath(), true, "my-tool");
 * SessionInfo s = client.createSession(SessionCreateRequest.defaults());
 * client.navigate(s.sessionId(), new NavigateRequest("http://localhost:3000"));
 * </pre>
 */
public class BrowserDriverClient {
    private static final ObjectMapper JSON = Json.mapper();
    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final String clientName;
    private final String tool;
    private final Duration timeout;

    public BrowserDriverClient(String baseUrl, String token, String clientName) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), stripSlash(baseUrl), token, clientName,
                null, Duration.ofSeconds(150));
    }

    private BrowserDriverClient(HttpClient http, String baseUrl, String token, String clientName, String tool, Duration timeout) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.token = token;
        this.clientName = clientName;
        this.tool = tool;
        this.timeout = timeout;
    }

    /** Reads driver.json. agent=true uses the agent token (for AI tools), false the admin token (IDE). */
    public static BrowserDriverClient fromConnectionFile(Path file, boolean agent, String clientName) {
        ConnectionInfo info = ConnectionFiles.read(file).orElseThrow(() -> new DriverApiException(0, "NOT_RUNNING",
                "Driver connection file not found at " + file + ". Start the driver first (scripts/*/start-driver)."));
        return new BrowserDriverClient(info.baseUrl(), agent ? info.agentToken() : info.adminToken(), clientName);
    }

    /** Copy that tags requests with the MCP tool name (driver records cdb.mcp.tool metrics). */
    public BrowserDriverClient forTool(String toolName) {
        return new BrowserDriverClient(http, baseUrl, token, clientName, toolName, timeout);
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ---------------------------------------------------------------- system

    public HealthStatus health() {
        return get("/api/v1/health", HealthStatus.class);
    }

    public boolean isUp() {
        try {
            return "UP".equals(health().status());
        } catch (DriverApiException e) {
            return false;
        }
    }

    public Capabilities capabilities() {
        return get("/api/v1/capabilities", Capabilities.class);
    }

    // ---------------------------------------------------------------- sessions

    public SessionInfo createSession(SessionCreateRequest request) {
        return send("POST", "/api/v1/sessions", request, SessionInfo.class);
    }

    public List<SessionInfo> listSessions() {
        return send("GET", "/api/v1/sessions", null, new TypeReference<>() { });
    }

    public SessionInfo getSession(String id) {
        return get(s(id), SessionInfo.class);
    }

    public void closeSession(String id) {
        send("DELETE", s(id), null, Void.class);
    }

    public SessionInfo restartSession(String id) {
        return send("POST", s(id) + "/restart", null, SessionInfo.class);
    }

    public SessionInfo setViewport(String id, ViewportRequest request) {
        return send("POST", s(id) + "/viewport", request, SessionInfo.class);
    }

    // ---------------------------------------------------------------- navigation

    public NavigationResult navigate(String id, NavigateRequest request) {
        return send("POST", s(id) + "/navigate", request, NavigationResult.class);
    }

    public NavigationResult refresh(String id, String tabId) {
        return send("POST", s(id) + "/refresh" + query(tab(tabId)), null, NavigationResult.class);
    }

    public NavigationResult back(String id, String tabId) {
        return send("POST", s(id) + "/back" + query(tab(tabId)), null, NavigationResult.class);
    }

    public NavigationResult forward(String id, String tabId) {
        return send("POST", s(id) + "/forward" + query(tab(tabId)), null, NavigationResult.class);
    }

    public PageInfo stop(String id, String tabId) {
        return send("POST", s(id) + "/stop" + query(tab(tabId)), null, PageInfo.class);
    }

    public PageInfo page(String id, String tabId) {
        return get(s(id) + "/page" + query(tab(tabId)), PageInfo.class);
    }

    public WaitResult waitFor(String id, WaitRequest request) {
        return send("POST", s(id) + "/wait", request, WaitResult.class);
    }

    public List<TabInfo> tabs(String id) {
        return send("GET", s(id) + "/tabs", null, new TypeReference<>() { });
    }

    public TabInfo openTab(String id, String url) {
        return send("POST", s(id) + "/tabs", new OpenTabRequest(url), TabInfo.class);
    }

    public void closeTab(String id, String tabId) {
        send("DELETE", s(id) + "/tabs/" + enc(tabId), null, Void.class);
    }

    public TabInfo activateTab(String id, String tabId) {
        return send("POST", s(id) + "/tabs/" + enc(tabId) + "/activate", null, TabInfo.class);
    }

    // ---------------------------------------------------------------- inspection

    public DomSnapshot dom(String id, SnapshotRequest r) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("tabId", r.tabId());
        q.put("mode", r.mode());
        q.put("root", r.root());
        q.put("maxElements", r.maxElements());
        q.put("includeHidden", r.includeHidden());
        q.put("role", r.role());
        q.put("text", r.text());
        return get(s(id) + "/dom" + query(q), DomSnapshot.class);
    }

    public ElementDetails inspect(String id, InspectRequest request) {
        return send("POST", s(id) + "/elements/inspect", request, ElementDetails.class);
    }

    public AccessibilityReport accessibility(String id, String tabId, String root) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("tabId", tabId);
        q.put("root", root);
        return get(s(id) + "/accessibility" + query(q), AccessibilityReport.class);
    }

    public EventPage<ConsoleEntry> console(String id, ConsoleQuery c) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("tabId", c.tabId());
        q.put("level", c.level());
        q.put("since", c.since());
        q.put("text", c.text());
        q.put("source", c.source());
        q.put("afterSeq", c.afterSeq());
        q.put("limit", c.limit());
        return send("GET", s(id) + "/console" + query(q), null, new TypeReference<>() { });
    }

    public EventPage<NetworkEntry> network(String id, NetworkQuery n) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("tabId", n.tabId());
        q.put("fetchXhrOnly", n.fetchXhrOnly());
        q.put("failedOnly", n.failedOnly());
        q.put("status", n.status());
        q.put("resourceType", n.resourceType());
        q.put("urlPattern", n.urlPattern());
        q.put("minDurationMs", n.minDurationMs());
        q.put("afterSeq", n.afterSeq());
        q.put("limit", n.limit());
        return send("GET", s(id) + "/network" + query(q), null, new TypeReference<>() { });
    }

    // ---------------------------------------------------------------- actions

    public ActionResult perform(String id, ActionRequest request) {
        return send("POST", s(id) + "/actions", request, ActionResult.class);
    }

    public EvaluateResult evaluate(String id, EvaluateRequest request) {
        return send("POST", s(id) + "/evaluate", request, EvaluateResult.class);
    }

    public ScreenshotInfo screenshot(String id, ScreenshotRequest request) {
        return send("POST", s(id) + "/screenshots", request, ScreenshotInfo.class);
    }

    public List<ScreenshotInfo> screenshots(String id) {
        return send("GET", s(id) + "/screenshots", null, new TypeReference<>() { });
    }

    public byte[] screenshotImage(String screenshotId) {
        HttpResponse<byte[]> r = exchange("GET", "/api/v1/screenshots/" + enc(screenshotId) + "/image", null,
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() >= 400) throw problem(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
        return r.body();
    }

    /** JPEG frame of the active tab for live previews (not stored). */
    public byte[] preview(String id, String tabId, int quality) {
        Map<String, Object> q = tab(tabId);
        q.put("quality", quality);
        HttpResponse<byte[]> r = exchange("GET", s(id) + "/preview" + query(q), null, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() >= 400) throw problem(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
        return r.body();
    }

    public CompareResult compare(CompareRequest request) {
        return send("POST", "/api/v1/screenshots/compare", request, CompareResult.class);
    }

    public BaselineInfo saveBaseline(BaselineRequest request) {
        return send("POST", "/api/v1/baselines", request, BaselineInfo.class);
    }

    public List<BaselineInfo> baselines() {
        return send("GET", "/api/v1/baselines", null, new TypeReference<>() { });
    }

    public ScenarioReport runScenario(String id, Scenario scenario) {
        return send("POST", s(id) + "/scenarios", scenario, ScenarioReport.class);
    }

    public ApprovalInfo approve(String id, ApprovalRequest request) {
        return send("POST", s(id) + "/approvals", request, ApprovalInfo.class);
    }

    public List<ApprovalInfo> approvals(String id) {
        return send("GET", s(id) + "/approvals", null, new TypeReference<>() { });
    }

    // ---------------------------------------------------------------- transport

    private static String s(String id) {
        return "/api/v1/sessions/" + enc(id);
    }

    private <T> T get(String path, Class<T> type) {
        return send("GET", path, null, type);
    }

    private <T> T send(String method, String path, Object body, Class<T> type) {
        HttpResponse<String> r = exchange(method, path, body, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) throw problem(r.statusCode(), r.body());
        if (type == Void.class || r.body() == null || r.body().isEmpty()) return null;
        try {
            return JSON.readValue(r.body(), type);
        } catch (IOException e) {
            throw new DriverApiException("Unexpected response from driver: " + e.getMessage(), e);
        }
    }

    private <T> T send(String method, String path, Object body, TypeReference<T> type) {
        HttpResponse<String> r = exchange(method, path, body, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) throw problem(r.statusCode(), r.body());
        try {
            return JSON.readValue(r.body(), type);
        } catch (IOException e) {
            throw new DriverApiException("Unexpected response from driver: " + e.getMessage(), e);
        }
    }

    private <T> HttpResponse<T> exchange(String method, String path, Object body, HttpResponse.BodyHandler<T> handler) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                    .header("Accept", "application/json");
            if (token != null) b.header("Authorization", "Bearer " + token);
            if (clientName != null) b.header("X-CDB-Client", clientName);
            if (tool != null) b.header("X-CDB-Tool", tool);
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
            } else {
                b.method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("") : HttpRequest.BodyPublishers.noBody());
            }
            return http.send(b.build(), handler);
        } catch (IOException e) {
            throw new DriverApiException("Cannot reach driver at " + baseUrl + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DriverApiException("Interrupted", e);
        }
    }

    private static DriverApiException problem(int status, String body) {
        try {
            JsonNode n = JSON.readTree(body);
            return new DriverApiException(status, n.path("code").asText("HTTP_" + status), n.path("detail").asText(body));
        } catch (Exception e) {
            return new DriverApiException(status, "HTTP_" + status, body);
        }
    }

    private static Map<String, Object> tab(String tabId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tabId", tabId);
        return m;
    }

    static String query(Map<String, ?> params) {
        StringJoiner j = new StringJoiner("&", "?", "");
        j.setEmptyValue("");
        params.forEach((k, v) -> {
            if (v != null) j.add(enc(k) + "=" + enc(String.valueOf(v)));
        });
        return j.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
