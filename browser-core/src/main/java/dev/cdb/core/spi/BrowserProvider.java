package dev.cdb.core.spi;

import dev.cdb.protocol.ProviderInfo;
import java.util.List;

/**
 * A browser runtime that can open isolated sessions. Implementations: playwright-provider.
 */
public interface BrowserProvider extends AutoCloseable {
    ProviderInfo info();

    /** Opens a new session (browser context + first tab). Blocks until ready. */
    BrowserSessionDriver open(SessionLaunchSpec spec, BrowserEventListener listener);

    /** Browser executables found on this machine (for diagnostics / capabilities). */
    default List<String> discoveredBrowsers() {
        return List.of();
    }

    @Override
    void close();
}
