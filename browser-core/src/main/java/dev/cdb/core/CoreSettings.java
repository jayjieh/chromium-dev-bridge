package dev.cdb.core;

import dev.cdb.protocol.Viewport;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Settings for the core engine. The driver maps Spring properties ("cdb.*") onto this record.
 *
 * @param dataDir             root for screenshots, baselines, profiles, audit logs
 * @param maxSessions         maximum concurrently open sessions
 * @param idleTimeout         sessions idle longer than this are closed
 * @param operationTimeout    default timeout for element actions and waits
 * @param navigationTimeout   default timeout for navigations
 * @param consoleBufferSize   ring-buffer size for console entries per session
 * @param networkBufferSize   ring-buffer size for network entries per session
 * @param captureBodies       capture request/response bodies (redacted, size-limited)
 * @param maxBodyBytes        body capture limit
 * @param allowedHosts        development host patterns (exact, "*.suffix", or "host:port")
 * @param allowFileUrls       allow file:// navigation
 * @param allowEvaluate       allow JavaScript evaluation endpoint (admin + approval still required)
 * @param screenshotsPerSession screenshot history retained per session
 * @param screenshotMaxAge    screenshots older than this are deleted
 * @param acceptDownloads     allow downloads (FILE_TRANSFER approval still required)
 * @param defaultHeadless     headless unless the request says otherwise
 * @param defaultViewport     viewport unless the request says otherwise
 * @param autoRestart         restart disconnected sessions automatically
 * @param sourceMaps          resolve source maps for console locations
 */
public record CoreSettings(
        Path dataDir,
        int maxSessions,
        Duration idleTimeout,
        Duration operationTimeout,
        Duration navigationTimeout,
        int consoleBufferSize,
        int networkBufferSize,
        boolean captureBodies,
        int maxBodyBytes,
        List<String> allowedHosts,
        boolean allowFileUrls,
        boolean allowEvaluate,
        int screenshotsPerSession,
        Duration screenshotMaxAge,
        boolean acceptDownloads,
        boolean defaultHeadless,
        Viewport defaultViewport,
        boolean autoRestart,
        boolean sourceMaps) {

    public static final List<String> DEFAULT_DEV_HOSTS = List.of("localhost", "127.0.0.1", "[::1]", "*.localhost", "*.test");

    public static CoreSettings defaults(Path dataDir) {
        return new CoreSettings(dataDir, 10, Duration.ofMinutes(30), Duration.ofSeconds(10), Duration.ofSeconds(30),
                1000, 1000, false, 64 * 1024, DEFAULT_DEV_HOSTS, false, false, 100, Duration.ofDays(7), false, true,
                new Viewport(1280, 800), true, true);
    }

    public CoreSettings withMaxSessions(int n) {
        return new CoreSettings(dataDir, n, idleTimeout, operationTimeout, navigationTimeout, consoleBufferSize,
                networkBufferSize, captureBodies, maxBodyBytes, allowedHosts, allowFileUrls, allowEvaluate,
                screenshotsPerSession, screenshotMaxAge, acceptDownloads, defaultHeadless, defaultViewport, autoRestart, sourceMaps);
    }

    public CoreSettings withAllowEvaluate(boolean b) {
        return new CoreSettings(dataDir, maxSessions, idleTimeout, operationTimeout, navigationTimeout, consoleBufferSize,
                networkBufferSize, captureBodies, maxBodyBytes, allowedHosts, allowFileUrls, b,
                screenshotsPerSession, screenshotMaxAge, acceptDownloads, defaultHeadless, defaultViewport, autoRestart, sourceMaps);
    }

    public CoreSettings withIdleTimeout(Duration d) {
        return new CoreSettings(dataDir, maxSessions, d, operationTimeout, navigationTimeout, consoleBufferSize,
                networkBufferSize, captureBodies, maxBodyBytes, allowedHosts, allowFileUrls, allowEvaluate,
                screenshotsPerSession, screenshotMaxAge, acceptDownloads, defaultHeadless, defaultViewport, autoRestart, sourceMaps);
    }

    public CoreSettings withBuffers(int console, int network) {
        return new CoreSettings(dataDir, maxSessions, idleTimeout, operationTimeout, navigationTimeout, console,
                network, captureBodies, maxBodyBytes, allowedHosts, allowFileUrls, allowEvaluate,
                screenshotsPerSession, screenshotMaxAge, acceptDownloads, defaultHeadless, defaultViewport, autoRestart, sourceMaps);
    }

    public Path screenshotsDir() {
        return dataDir.resolve("screenshots");
    }

    public Path baselinesDir() {
        return dataDir.resolve("baselines");
    }

    public Path profilesDir() {
        return dataDir.resolve("profiles");
    }

    public Path downloadsDir() {
        return dataDir.resolve("downloads");
    }
}
