package dev.cdb.provider.extension;

import dev.cdb.core.spi.SessionOpener;

/** Bridges the hub to the session manager (implemented by the driver). */
public interface ExtensionSessionRegistrar {
    /** Creates a session for a shared tab; returns its session id. */
    String attach(ExtensionTab tab, SessionOpener opener);

    /** Closes the session of a tab that is no longer shared. */
    void detach(String sessionId);

    /** Approves the origin of the shared page (the user ticked "allow this site" in the popup). */
    void approveOrigin(String sessionId, String origin);
}
