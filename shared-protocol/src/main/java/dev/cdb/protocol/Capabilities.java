package dev.cdb.protocol;

import java.util.List;
import java.util.Map;

public record Capabilities(String version, List<ProviderInfo> providers, List<SnapshotMode> snapshotModes,
                           List<ActionType> actionTypes, List<String> eventTypes, Map<String, Viewport> viewportPresets,
                           boolean evaluateEnabled, List<String> allowedHosts, int maxSessions,
                           List<String> discoveredBrowsers) {
}
