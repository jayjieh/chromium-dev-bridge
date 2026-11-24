package dev.cdb.driver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Externalised driver configuration ("cdb.*"). */
@ConfigurationProperties(prefix = "cdb")
public record CdbProperties(Path dataDir, Browser browser, Sessions sessions, Capture capture, Screenshots screenshots,
                            Security security, Audit audit) {

    public record Browser(Path executablePath, String channel, boolean install, boolean headless, int viewportWidth,
                          int viewportHeight, Duration pumpInterval, List<String> args) {
    }

    public record Sessions(int max, Duration idleTimeout, Duration operationTimeout, Duration navigationTimeout,
                           int consoleBuffer, int networkBuffer, boolean autoRestart) {
    }

    public record Capture(boolean bodies, int maxBodyBytes, boolean sourceMaps) {
    }

    public record Screenshots(int perSession, Duration maxAge) {
    }

    public record Security(List<String> allowedHosts, boolean allowFileUrls, boolean allowEvaluate, boolean acceptDownloads,
                           boolean enforceNavigationGuard, List<String> allowedOrigins, List<String> allowedHostHeaders,
                           int rateLimitPerSecond, int rateLimitBurst, long maxRequestBytes, int maxWebsocketMessageBytes,
                           String adminToken, String agentToken, Path connectionFile,
                           List<String> extensionIds, boolean extensionCanApprove) {
    }

    public record Audit(boolean enabled, int retentionDays) {
    }
}
