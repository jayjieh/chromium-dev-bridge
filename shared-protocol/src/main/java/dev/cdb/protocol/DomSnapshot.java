package dev.cdb.protocol;

import java.util.List;

public record DomSnapshot(String pageTitle, String url, String tabId, SnapshotMode mode, int elementCount,
                          boolean truncated, List<DomElement> elements, String ariaSnapshot) {
}
