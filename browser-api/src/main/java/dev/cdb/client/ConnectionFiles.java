package dev.cdb.client;

import dev.cdb.protocol.ConnectionInfo;
import dev.cdb.protocol.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Locates and reads the driver connection file (~/.chromium-dev-bridge/driver.json). */
public final class ConnectionFiles {
    private ConnectionFiles() {
    }

    public static Path defaultPath() {
        String override = System.getenv("CDB_CONNECTION_FILE");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".chromium-dev-bridge", "driver.json");
    }

    public static Optional<ConnectionInfo> read(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(Json.mapper().readValue(file.toFile(), ConnectionInfo.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
