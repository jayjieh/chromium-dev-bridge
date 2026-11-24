package dev.cdb.protocol;

public record NetworkQuery(String tabId, Boolean fetchXhrOnly, Boolean failedOnly, String status, String resourceType,
                           String urlPattern, Integer minDurationMs, Long afterSeq, Integer limit) {
    public static NetworkQuery all() {
        return new NetworkQuery(null, null, null, null, null, null, null, null, null);
    }

    public static NetworkQuery failed() {
        return new NetworkQuery(null, null, true, null, null, null, null, null, null);
    }
}
