package dev.cdb.protocol;

import java.util.List;

public record AccessibilityReport(String tabId, String url, String ariaSnapshot, List<AccessibilityIssue> issues) {
}
