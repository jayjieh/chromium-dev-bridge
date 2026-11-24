package dev.cdb.core.spi;

import dev.cdb.protocol.ConsoleEntry;
import dev.cdb.protocol.NetworkEntry;
import dev.cdb.protocol.TabInfo;

/**
 * Callbacks from a provider. Invoked on the provider's browser thread: implementations must not block.
 * Entries are raw (unredacted); the core redacts before storing or publishing.
 */
public interface BrowserEventListener {
    void onConsole(ConsoleEntry entry);

    void onNetwork(NetworkEntry entry);

    void onTabCreated(TabInfo tab);

    void onTabClosed(String tabId);

    void onNavigation(String tabId, String url, NavigationPhase phase, String error);

    void onNavigationBlocked(String tabId, String url);

    void onDisconnected(String reason);

    enum NavigationPhase { STARTED, COMPLETED, FAILED }

    BrowserEventListener NOOP = new BrowserEventListener() {
        public void onConsole(ConsoleEntry entry) { }
        public void onNetwork(NetworkEntry entry) { }
        public void onTabCreated(TabInfo tab) { }
        public void onTabClosed(String tabId) { }
        public void onNavigation(String tabId, String url, NavigationPhase phase, String error) { }
        public void onNavigationBlocked(String tabId, String url) { }
        public void onDisconnected(String reason) { }
    };
}
