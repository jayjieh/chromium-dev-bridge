package dev.cdb.core.spi;

/**
 * Opens the browser side of a session. {@link BrowserProvider#open} is one implementation; the Chrome extension hub
 * supplies another for tabs the user shares from their own browser.
 */
@FunctionalInterface
public interface SessionOpener {
    BrowserSessionDriver open(SessionLaunchSpec spec, BrowserEventListener listener);
}
