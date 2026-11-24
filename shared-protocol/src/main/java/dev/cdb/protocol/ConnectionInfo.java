package dev.cdb.protocol;

import java.time.Instant;

/** Contents of ~/.chromium-dev-bridge/driver.json, written by the driver at start-up. */
public record ConnectionInfo(String baseUrl, int port, String adminToken, String agentToken, long pid, Instant startedAt,
                             String version, String extensionToken) {

    /** Pairing code for the Chrome extension: host:port#token. */
    public String extensionPairingCode() {
        return extensionToken == null ? null : "127.0.0.1:" + port + "#" + extensionToken;
    }
}
