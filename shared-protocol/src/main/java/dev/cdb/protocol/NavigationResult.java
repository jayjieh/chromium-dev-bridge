package dev.cdb.protocol;

public record NavigationResult(String tabId, String url, String title, Integer status, long durationMs) {
}
