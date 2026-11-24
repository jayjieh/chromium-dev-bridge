package dev.cdb.protocol;

public record WaitRequest(String tabId, Locator locator, ElementState state, WaitUntil loadState, String urlPattern,
                          String text, Integer timeoutMs) {
}
