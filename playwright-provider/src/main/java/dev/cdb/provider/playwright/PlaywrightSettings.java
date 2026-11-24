package dev.cdb.provider.playwright;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * @param executablePath     explicit Chromium/Chrome executable (null = Playwright-managed Chromium)
 * @param defaultChannel     "chrome", "msedge" ... (null = bundled Chromium)
 * @param extraArgs          extra browser command-line switches
 * @param installBrowser     download Playwright's Chromium (only Chromium) when missing
 * @param pumpInterval       idle event pump interval
 * @param enforceNavigationGuard route top-level navigations through the URL policy
 */
public record PlaywrightSettings(Path executablePath, String defaultChannel, List<String> extraArgs, boolean installBrowser,
                                 Duration pumpInterval, boolean enforceNavigationGuard) {
    public static PlaywrightSettings defaults() {
        return new PlaywrightSettings(null, null, List.of(), true, Duration.ofMillis(20), true);
    }
}
