package dev.cdb.core.spi;

import dev.cdb.protocol.SessionIsolation;
import dev.cdb.protocol.Viewport;
import java.nio.file.Path;
import java.time.Duration;

/** Everything a provider needs to open a session. Paths are already validated by the core. */
public record SessionLaunchSpec(
        String sessionId,
        boolean headless,
        Viewport viewport,
        String locale,
        Double deviceScaleFactor,
        String userAgent,
        SessionIsolation isolation,
        Path profileDir,
        String channel,
        String connectEndpoint,
        Path downloadDir,
        boolean acceptDownloads,
        NavigationGuard guard,
        Duration operationTimeout,
        Duration navigationTimeout,
        boolean captureBodies,
        int maxBodyBytes) {
}
