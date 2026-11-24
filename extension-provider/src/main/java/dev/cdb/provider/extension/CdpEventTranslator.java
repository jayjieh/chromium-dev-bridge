package dev.cdb.provider.extension;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cdb.core.console.StackTraceParser;
import dev.cdb.core.spi.BrowserEventListener;
import dev.cdb.protocol.ConsoleEntry;
import dev.cdb.protocol.ConsoleKind;
import dev.cdb.protocol.ConsoleLevel;
import dev.cdb.protocol.NetworkEntry;
import dev.cdb.protocol.NetworkState;
import dev.cdb.protocol.SourceLocation;
import dev.cdb.protocol.StackFrame;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Converts raw CDP Runtime/Log/Network events into protocol console and network entries (unredacted; the core
 * redacts). Keeps per-request state between requestWillBeSent, responseReceived and loadingFinished/Failed.
 */
final class CdpEventTranslator {
    private final String tabId;
    private final BrowserEventListener listener;
    private final Map<String, Req> requests = new LinkedHashMap<>();
    private final Map<String, Integer> documentStatus = new LinkedHashMap<>();
    /** Execution contexts that belong to other browser extensions (content-script isolated worlds). */
    private final java.util.Set<Integer> extensionContexts = new java.util.HashSet<>();

    /**
     * Messages only Chrome's extension messaging APIs produce. Used when an error carries no execution context to
     * attribute it to (e.g. rejections from chrome.runtime.sendMessage in another extension's content script).
     */
    private static final java.util.List<String> EXTENSION_MESSAGES = java.util.List.of(
            "message channel closed before a response was received",
            "Could not establish connection. Receiving end does not exist",
            "Extension context invalidated",
            "The message port closed before a response was received");

    private static final class Req {
        String entryId;
        String method;
        String url;
        String type;
        Map<String, String> headers;
        String postData;
        Instant started;
        double startTs;
        Integer status;
        String statusText;
        String mime;
        Map<String, String> responseHeaders;
        String redirectedFrom;
        int redirects;
        boolean webSocket;
    }

    CdpEventTranslator(String tabId, BrowserEventListener listener) {
        this.tabId = tabId;
        this.listener = listener;
    }

    synchronized int inflight() {
        int n = 0;
        for (Req r : requests.values()) if (!r.webSocket) n++;
        return n;
    }

    synchronized Integer documentStatus(String loaderId) {
        return loaderId == null ? null : documentStatus.get(loaderId);
    }

    synchronized void handle(String method, JsonNode p, boolean captureBodies) {
        switch (method) {
            case "Runtime.executionContextCreated" -> contextCreated(p.path("context"));
            case "Runtime.executionContextDestroyed" -> extensionContexts.remove(p.path("executionContextId").asInt(-1));
            case "Runtime.executionContextsCleared" -> extensionContexts.clear();
            case "Runtime.consoleAPICalled" -> {
                if (!fromOtherExtension(p.path("executionContextId"), p.path("stackTrace"), null)) console(p);
            }
            case "Runtime.exceptionThrown" -> {
                JsonNode d = p.path("exceptionDetails");
                String text = d.path("exception").path("description").asText(d.path("text").asText(""));
                if (!fromOtherExtension(d.path("executionContextId"), d.path("stackTrace"), text)) exception(d);
            }
            case "Log.entryAdded" -> log(p.path("entry"));
            case "Network.requestWillBeSent" -> requestWillBeSent(p, captureBodies);
            case "Network.responseReceived" -> responseReceived(p);
            case "Network.loadingFinished" -> finished(p);
            case "Network.loadingFailed" -> failed(p);
            case "Network.webSocketCreated" -> webSocketCreated(p);
            case "Network.webSocketClosed" -> webSocketEnded(p, null);
            case "Network.webSocketFrameError" -> webSocketEnded(p, p.path("errorMessage").asText("WebSocket error"));
            default -> { }
        }
    }

    // ---------------------------------------------------------------- other extensions

    private void contextCreated(JsonNode ctx) {
        String origin = ctx.path("origin").asText("");
        String type = ctx.path("auxData").path("type").asText("");
        if (origin.startsWith("chrome-extension://") || type.equals("isolated")) extensionContexts.add(ctx.path("id").asInt());
    }

    /** True for console output and errors that come from other extensions rather than the page under test. */
    boolean fromOtherExtension(JsonNode contextId, JsonNode stackTrace, String errorText) {
        if (contextId.canConvertToInt() && extensionContexts.contains(contextId.asInt())) return true;
        // Only when every frame is extension code: devtools extensions wrap console.error, so a page's own message can
        // have an extension frame on top of the page frames.
        JsonNode frames = stackTrace.path("callFrames");
        boolean allExtension = frames.size() > 0;
        for (JsonNode f : frames) {
            if (!f.path("url").asText("").startsWith("chrome-extension://")) allExtension = false;
        }
        if (allExtension) return true;
        if (errorText != null) {
            for (String m : EXTENSION_MESSAGES) if (errorText.contains(m)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- console

    private void console(JsonNode p) {
        String type = p.path("type").asText("log");
        List<String> parts = new ArrayList<>();
        for (JsonNode a : p.path("args")) parts.add(argText(a));
        String text = String.join(" ", parts);
        List<StackFrame> frames = frames(p.path("stackTrace"));
        SourceLocation src = frames.isEmpty() ? null : frames.get(0).location();
        ConsoleLevel level = ConsoleLevel.fromConsoleType(type);
        emitConsole(level, ConsoleKind.CONSOLE, text, src, level == ConsoleLevel.ERROR ? frames : List.of());
    }

    static String argText(JsonNode a) {
        if (a.has("value")) {
            JsonNode v = a.get("value");
            return v.isTextual() ? v.asText() : v.toString();
        }
        if (a.has("unserializableValue")) return a.get("unserializableValue").asText();
        if (a.has("description")) return a.get("description").asText();
        return a.path("type").asText("undefined");
    }

    private void exception(JsonNode d) {
        String text = d.path("text").asText("");
        String description = d.path("exception").path("description").asText(null);
        if (description == null) description = d.path("exception").path("value").asText(text);
        boolean rejection = text.contains("(in promise)");
        List<StackFrame> frames = StackTraceParser.parse(description);
        if (frames.isEmpty()) frames = frames(d.path("stackTrace"));
        String message = StackTraceParser.message(description);
        int nl = message.indexOf('\n');
        if (nl > 0) message = message.substring(0, nl).trim();
        if (message.isBlank()) message = text;
        SourceLocation src = !frames.isEmpty() ? frames.get(0).location()
                : d.hasNonNull("url") ? SourceLocation.ofUrl(d.get("url").asText(), d.path("lineNumber").asInt() + 1,
                d.path("columnNumber").asInt() + 1) : null;
        emitConsole(ConsoleLevel.ERROR, rejection ? ConsoleKind.UNHANDLED_REJECTION : ConsoleKind.PAGE_ERROR,
                rejection ? "Unhandled promise rejection: " + message : message, src, frames);
    }

    private void log(JsonNode e) {
        String level = e.path("level").asText("info");
        if (!level.equals("error") && !level.equals("warning")) return;
        String url = e.path("url").asText(null);
        SourceLocation src = url == null || url.isEmpty() ? null
                : SourceLocation.ofUrl(url, e.path("lineNumber").asInt(0) + 1, 1);
        emitConsole(ConsoleLevel.fromConsoleType(level), ConsoleKind.CONSOLE, e.path("text").asText(""), src, List.of());
    }

    private static List<StackFrame> frames(JsonNode stackTrace) {
        List<StackFrame> out = new ArrayList<>();
        for (JsonNode f : stackTrace.path("callFrames")) {
            String url = f.path("url").asText("");
            // Skip frames injected by other browser extensions (e.g. DevTools hooks wrapping console.error).
            if (url.isEmpty() || url.startsWith("chrome-extension://")) continue;
            String fn = f.path("functionName").asText("");
            out.add(new StackFrame(fn.isEmpty() ? null : fn,
                    SourceLocation.ofUrl(url, f.path("lineNumber").asInt() + 1, f.path("columnNumber").asInt() + 1), null));
            if (out.size() >= 50) break;
        }
        return out;
    }

    private void emitConsole(ConsoleLevel level, ConsoleKind kind, String text, SourceLocation src, List<StackFrame> frames) {
        String msg = text.length() > 10_000 ? text.substring(0, 10_000) + "...[truncated]" : text;
        listener.onConsole(new ConsoleEntry(0, UUID.randomUUID().toString(), null, tabId, Instant.now(), level, kind, msg, src,
                null, frames));
    }

    // ---------------------------------------------------------------- network

    private void requestWillBeSent(JsonNode p, boolean captureBodies) {
        String id = p.path("requestId").asText();
        Req previous = requests.get(id);
        if (previous != null && p.has("redirectResponse")) {
            JsonNode rr = p.get("redirectResponse");
            previous.status = rr.path("status").asInt();
            previous.statusText = rr.path("statusText").asText(null);
            previous.responseHeaders = headers(rr.path("headers"));
            emit(previous, NetworkState.COMPLETED, (p.path("timestamp").asDouble() - previous.startTs) * 1000, null, false);
        }
        Req r = new Req();
        r.redirects = previous == null ? 0 : previous.redirects + 1;
        r.entryId = r.redirects == 0 ? "cdp-" + id : "cdp-" + id + "-" + r.redirects;
        r.redirectedFrom = previous == null ? null : previous.url;
        JsonNode req = p.path("request");
        r.method = req.path("method").asText("GET");
        r.url = req.path("url").asText("");
        r.type = p.path("type").asText("Other").toLowerCase(Locale.ROOT);
        r.headers = headers(req.path("headers"));
        r.postData = captureBodies ? req.path("postData").asText(null) : null;
        r.started = p.hasNonNull("wallTime") ? Instant.ofEpochMilli((long) (p.get("wallTime").asDouble() * 1000)) : Instant.now();
        r.startTs = p.path("timestamp").asDouble();
        requests.put(id, r);
        trim();
        emit(r, NetworkState.PENDING, null, null, false);
    }

    private void responseReceived(JsonNode p) {
        Req r = requests.get(p.path("requestId").asText());
        if (r == null) return;
        JsonNode resp = p.path("response");
        r.status = resp.path("status").asInt();
        r.statusText = resp.path("statusText").asText(null);
        r.mime = resp.path("mimeType").asText(null);
        r.responseHeaders = headers(resp.path("headers"));
        if ("Document".equals(p.path("type").asText()) && p.hasNonNull("loaderId")) {
            documentStatus.put(p.get("loaderId").asText(), r.status);
            while (documentStatus.size() > 100) documentStatus.remove(documentStatus.keySet().iterator().next());
        }
    }

    private void finished(JsonNode p) {
        Req r = requests.remove(p.path("requestId").asText());
        if (r == null) return;
        emit(r, NetworkState.COMPLETED, (p.path("timestamp").asDouble() - r.startTs) * 1000, null, false);
    }

    private void failed(JsonNode p) {
        Req r = requests.remove(p.path("requestId").asText());
        if (r == null) return;
        String error = p.path("errorText").asText("failed");
        if (p.hasNonNull("blockedReason")) error = error + " (blocked: " + p.get("blockedReason").asText() + ")";
        boolean cors = p.hasNonNull("corsErrorStatus");
        if (cors) error = error + " (CORS: " + p.path("corsErrorStatus").path("corsError").asText() + ")";
        emit(r, NetworkState.FAILED, (p.path("timestamp").asDouble() - r.startTs) * 1000, error, cors);
    }

    private void webSocketCreated(JsonNode p) {
        Req r = new Req();
        r.entryId = "cdp-ws-" + p.path("requestId").asText();
        r.method = "GET";
        r.url = p.path("url").asText("");
        r.type = "websocket";
        r.webSocket = true;
        r.started = Instant.now();
        r.status = 101;
        r.headers = Map.of();
        requests.put(p.path("requestId").asText(), r);
        emit(r, NetworkState.PENDING, null, null, false);
    }

    private void webSocketEnded(JsonNode p, String error) {
        Req r = requests.remove(p.path("requestId").asText());
        if (r == null) return;
        emit(r, error == null ? NetworkState.COMPLETED : NetworkState.FAILED,
                (double) (Instant.now().toEpochMilli() - r.started.toEpochMilli()), error, false);
    }

    private void emit(Req r, NetworkState state, Double durationMs, String failure, boolean cors) {
        Double d = durationMs == null ? null : Math.round(durationMs * 10) / 10.0;
        listener.onNetwork(new NetworkEntry(0, r.entryId, null, tabId, r.method, r.url, r.type, state,
                state == NetworkState.FAILED && !r.webSocket ? null : r.status, r.statusText, r.mime, r.started, d, failure,
                r.redirectedFrom, cors, r.webSocket, r.headers, r.responseHeaders, r.postData, null));
    }

    private void trim() {
        Iterator<String> it = requests.keySet().iterator();
        while (requests.size() > 5000 && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static Map<String, String> headers(JsonNode h) {
        Map<String, String> out = new LinkedHashMap<>();
        h.fields().forEachRemaining(e -> out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().asText()));
        return out;
    }
}
