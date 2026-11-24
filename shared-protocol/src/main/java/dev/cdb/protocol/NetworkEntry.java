package dev.cdb.protocol;

import java.time.Instant;
import java.util.Map;

public record NetworkEntry(long seq, String requestId, String sessionId, String tabId, String method, String url,
                           String resourceType, NetworkState state, Integer status, String statusText, String mimeType,
                           Instant startedAt, Double durationMs, String failureText, String redirectedFrom,
                           boolean corsError, boolean webSocket, Map<String, String> requestHeaders,
                           Map<String, String> responseHeaders, String requestBody, String responseBody) {

    public NetworkEntry withSeq(long s) {
        return new NetworkEntry(s, requestId, sessionId, tabId, method, url, resourceType, state, status, statusText, mimeType,
                startedAt, durationMs, failureText, redirectedFrom, corsError, webSocket, requestHeaders, responseHeaders,
                requestBody, responseBody);
    }

    public boolean isFailure() {
        return state == NetworkState.FAILED || (status != null && status >= 400);
    }
}
