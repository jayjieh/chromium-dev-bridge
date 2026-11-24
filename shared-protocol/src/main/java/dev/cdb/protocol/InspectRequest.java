package dev.cdb.protocol;

import java.util.List;

public record InspectRequest(String tabId, Locator locator, List<String> styles) {
}
