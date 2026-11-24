package dev.cdb.provider.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cdb.core.BridgeException;
import dev.cdb.core.security.UrlPolicy;
import dev.cdb.protocol.ErrorCode;
import dev.cdb.protocol.ProviderInfo;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON-RPC 2.0 peer for one extension. Extension → driver: hello, ping, tab.shared, tab.unshared, cdp.event.
 * Driver → extension: cdp.send, tab.reattach, tab.activate (requests); tab.unshare, session.attached (notifications).
 */
final class ExtensionConnection {
    private static final Logger log = LoggerFactory.getLogger(ExtensionConnection.class);

    private final ExtensionHub.Transport transport;
    private final ExtensionSessionRegistrar registrar;
    private final ExecutorService lifecycle;
    private final Duration timeout;
    private final boolean allowApprove;
    private final ObjectMapper mapper;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<Integer, ExtensionSessionDriver> tabs = new ConcurrentHashMap<>();
    private volatile String extensionVersion = "?";
    private volatile String browserVersion;
    private volatile boolean open = true;

    ExtensionConnection(ExtensionHub.Transport transport, ExtensionSessionRegistrar registrar, ExecutorService lifecycle,
                        Duration timeout, boolean allowApprove, ObjectMapper mapper) {
        this.transport = transport;
        this.registrar = registrar;
        this.lifecycle = lifecycle;
        this.timeout = timeout;
        this.allowApprove = allowApprove;
        this.mapper = mapper;
    }

    ProviderInfo providerInfo() {
        return new ProviderInfo("chrome-extension", "Chrome (shared tab)", extensionVersion, "chrome", browserVersion);
    }

    ExtensionHub.ConnectionStatus status() {
        return new ExtensionHub.ConnectionStatus(transport.id(), extensionVersion, browserVersion, new ArrayList<>(tabs.keySet()));
    }

    // ---------------------------------------------------------------- outgoing

    JsonNode call(String method, JsonNode params, Duration callTimeout) {
        if (!open) throw new BridgeException(ErrorCode.SESSION_NOT_RUNNING, "Chrome extension disconnected");
        long id = ids.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        ObjectNode msg = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        msg.set("params", params);
        send(msg);
        try {
            return f.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new BridgeException(ErrorCode.TIMEOUT, method + " timed out after " + callTimeout.toMillis()
                    + " ms (is the tab hidden or the browser busy?)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BridgeException(ErrorCode.INTERNAL, "Interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof BridgeException be) throw be;
            throw new BridgeException(ErrorCode.BROWSER_ERROR, c.getMessage(), c);
        } finally {
            pending.remove(id);
        }
    }

    JsonNode cdp(int tabId, String method, JsonNode params, Duration callTimeout) {
        ObjectNode p = mapper.createObjectNode().put("tabId", tabId).put("method", method);
        p.set("params", params == null ? mapper.createObjectNode() : params);
        return call("cdp.send", p, callTimeout);
    }

    void notify(String method, JsonNode params) {
        if (!open) return;
        ObjectNode msg = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        msg.set("params", params);
        send(msg);
    }

    private void send(JsonNode msg) {
        try {
            transport.send(mapper.writeValueAsString(msg));
        } catch (Exception e) {
            throw new BridgeException(ErrorCode.BROWSER_ERROR, "Could not reach the Chrome extension: " + e.getMessage(), e);
        }
    }

    ObjectMapper mapper() {
        return mapper;
    }

    Duration timeout() {
        return timeout;
    }

    void forget(int tabId, ExtensionSessionDriver driver) {
        tabs.remove(tabId, driver);
    }

    // ---------------------------------------------------------------- incoming

    void handle(String text) {
        JsonNode msg;
        try {
            msg = mapper.readTree(text);
        } catch (Exception e) {
            log.debug("Ignoring malformed extension message");
            return;
        }
        JsonNode id = msg.get("id");
        String method = msg.path("method").asText(null);
        if (method == null) {
            if (id != null && id.canConvertToLong()) {
                CompletableFuture<JsonNode> f = pending.get(id.asLong());
                if (f != null) {
                    if (msg.has("error")) {
                        f.completeExceptionally(new BridgeException(ErrorCode.BROWSER_ERROR,
                                msg.path("error").path("message").asText("Chrome error")));
                    } else {
                        f.complete(msg.path("result"));
                    }
                }
            }
            return;
        }
        JsonNode params = msg.path("params");
        switch (method) {
            case "hello" -> {
                extensionVersion = params.path("version").asText("?");
                browserVersion = parseChromeVersion(params.path("userAgent").asText(""));
                reply(id, mapper.createObjectNode().put("driver", "chromium-dev-bridge"));
            }
            case "ping" -> reply(id, mapper.getNodeFactory().textNode("pong"));
            case "cdp.event" -> {
                ExtensionSessionDriver d = tabs.get(params.path("tabId").asInt(-1));
                if (d != null) d.onEvent(params.path("method").asText(), params.path("params"));
            }
            case "tab.shared" -> lifecycle.execute(() -> share(id, params));
            case "tab.unshared" -> {
                ExtensionSessionDriver d = tabs.remove(params.path("tabId").asInt(-1));
                if (d != null) {
                    d.detachedByBrowser(params.path("reason").asText("unshared"));
                    lifecycle.execute(() -> detach(d));
                }
            }
            case "tab.updated" -> {
                ExtensionSessionDriver d = tabs.get(params.path("tabId").asInt(-1));
                if (d != null) d.tabUpdated(params.path("url").asText(null), params.path("title").asText(null));
            }
            default -> {
                if (id != null) replyError(id, -32601, "Method not found: " + method);
            }
        }
    }

    private void share(JsonNode id, JsonNode params) {
        int tabId = params.path("tabId").asInt(-1);
        if (tabId < 0) {
            replyError(id, -32602, "tabId is required");
            return;
        }
        ExtensionSessionDriver existing = tabs.get(tabId);
        if (existing != null && existing.sessionId() != null) {
            reply(id, mapper.createObjectNode().put("sessionId", existing.sessionId()));
            return;
        }
        String url = params.path("url").asText("");
        ExtensionTab tab = new ExtensionTab(transport.id(), tabId, url, params.path("title").asText(""),
                params.path("agentAccess").asBoolean(true), params.path("approveOrigin").asBoolean(false));
        try {
            String sessionId = registrar.attach(tab, (spec, listener) -> {
                ExtensionSessionDriver d = new ExtensionSessionDriver(this, tabId, spec, listener);
                tabs.put(tabId, d);
                try {
                    d.init();
                } catch (RuntimeException e) {
                    tabs.remove(tabId, d);
                    throw e;
                }
                return d;
            });
            ExtensionSessionDriver d = tabs.get(tabId);
            if (d != null) d.sessionId(sessionId);
            if (tab.approveOrigin() && allowApprove) {
                String origin = originOf(url);
                if (origin != null) registrar.approveOrigin(sessionId, origin);
            }
            reply(id, mapper.createObjectNode().put("sessionId", sessionId));
            notify("session.attached", mapper.createObjectNode().put("tabId", tabId).put("sessionId", sessionId));
        } catch (RuntimeException e) {
            log.warn("Sharing tab {} failed: {}", tabId, e.getMessage());
            replyError(id, -32000, e.getMessage());
        }
    }

    private void detach(ExtensionSessionDriver d) {
        if (d.sessionId() == null) return;
        try {
            registrar.detach(d.sessionId());
        } catch (RuntimeException e) {
            log.debug("Detach of {} failed: {}", d.sessionId(), e.toString());
        }
    }

    void closed() {
        open = false;
        pending.values().forEach(f -> f.completeExceptionally(
                new BridgeException(ErrorCode.SESSION_NOT_RUNNING, "Chrome extension disconnected")));
        pending.clear();
        for (ExtensionSessionDriver d : tabs.values()) {
            d.detachedByBrowser("extension disconnected");
            lifecycle.execute(() -> detach(d));
        }
        tabs.clear();
    }

    private void reply(JsonNode id, JsonNode result) {
        if (id == null || id.isNull()) return;
        ObjectNode n = mapper.createObjectNode().put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("result", result);
        send(n);
    }

    private void replyError(JsonNode id, int code, String message) {
        if (id == null || id.isNull()) return;
        ObjectNode n = mapper.createObjectNode().put("jsonrpc", "2.0");
        n.set("id", id);
        n.putObject("error").put("code", code).put("message", message == null ? "error" : message);
        send(n);
    }

    static String originOf(String url) {
        try {
            URI u = URI.create(url);
            if (u.getHost() == null || u.getScheme() == null) return null;
            if (!u.getScheme().equals("http") && !u.getScheme().equals("https")) return null;
            return UrlPolicy.origin(u);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String parseChromeVersion(String userAgent) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/([0-9.]+)").matcher(userAgent);
        return m.find() ? m.group(1) : null;
    }
}
