package dev.cdb.protocol;

import java.time.Instant;
import java.util.UUID;

/** Event published on the driver event bus and streamed over WebSocket. */
public record DriverEvent(String eventId, String type, String sessionId, String tabId, Instant timestamp, Object data) {
    public static DriverEvent of(String type, String sessionId, String tabId, Object data) {
        return new DriverEvent(UUID.randomUUID().toString(), type, sessionId, tabId, Instant.now(), data);
    }
}
