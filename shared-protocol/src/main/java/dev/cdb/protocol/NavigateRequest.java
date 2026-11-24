package dev.cdb.protocol;

public record NavigateRequest(String url, String tabId, WaitUntil waitUntil, Integer timeoutMs) {
    public NavigateRequest(String url) {
        this(url, null, null, null);
    }
}
