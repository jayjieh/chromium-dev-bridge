package dev.cdb.protocol;

import java.time.Instant;

public record SessionInfo(
        String sessionId,
        String projectId,
        String label,
        String contextId,
        String activeTabId,
        String currentUrl,
        String title,
        Instant createdAt,
        Instant lastActivityAt,
        SessionState state,
        ProviderInfo provider,
        boolean headless,
        Viewport viewport,
        String owner,
        boolean agentAccess,
        String failureReason) {
}
