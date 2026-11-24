package dev.cdb.provider.extension;

/** A tab the user shared from the extension popup. */
public record ExtensionTab(String connectionId, int tabId, String url, String title, boolean agentAccess, boolean approveOrigin) {
}
