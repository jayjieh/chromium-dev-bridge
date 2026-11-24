package dev.cdb.protocol;

public record SnapshotRequest(String tabId, SnapshotMode mode, String root, Integer maxElements, Boolean includeHidden,
                              String role, String text) {
    public static SnapshotRequest of(SnapshotMode mode) {
        return new SnapshotRequest(null, mode, null, null, null, null, null);
    }
}
