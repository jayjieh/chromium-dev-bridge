package dev.cdb.protocol;

import java.time.Instant;

/** Contents of ~/.chromium-dev-bridge/driver.json, written by the driver at start-up. */
public record ConnectionInfo(String baseUrl, int port, String adminToken, String agentToken, long pid, Instant startedAt,
                             String version) {
}
