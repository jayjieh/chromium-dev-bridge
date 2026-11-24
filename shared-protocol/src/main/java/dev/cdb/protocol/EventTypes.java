package dev.cdb.protocol;

import java.util.List;

/** Event type names streamed over the WebSocket protocol. */
public final class EventTypes {
    public static final String SESSION_CREATED = "browser.session.created";
    public static final String SESSION_CLOSED = "browser.session.closed";
    public static final String SESSION_STATE = "browser.session.state";
    public static final String NAVIGATION_STARTED = "browser.navigation.started";
    public static final String NAVIGATION_COMPLETED = "browser.navigation.completed";
    public static final String NAVIGATION_FAILED = "browser.navigation.failed";
    public static final String NAVIGATION_BLOCKED = "browser.navigation.blocked";
    public static final String CONSOLE_MESSAGE = "browser.console.message";
    public static final String CONSOLE_ERROR = "browser.console.error";
    public static final String NETWORK_REQUEST = "browser.network.request";
    public static final String NETWORK_RESPONSE = "browser.network.response";
    public static final String NETWORK_FAILED = "browser.network.failed";
    public static final String SCREENSHOT_CREATED = "browser.screenshot.created";
    public static final String TAB_CREATED = "browser.tab.created";
    public static final String TAB_CLOSED = "browser.tab.closed";

    public static final List<String> ALL = List.of(SESSION_CREATED, SESSION_CLOSED, SESSION_STATE, NAVIGATION_STARTED,
            NAVIGATION_COMPLETED, NAVIGATION_FAILED, NAVIGATION_BLOCKED, CONSOLE_MESSAGE, CONSOLE_ERROR, NETWORK_REQUEST,
            NETWORK_RESPONSE, NETWORK_FAILED, SCREENSHOT_CREATED, TAB_CREATED, TAB_CLOSED);

    /** Matches an event type against a pattern such as "browser.console.*" or "*". */
    public static boolean matches(String pattern, String type) {
        if (pattern == null || pattern.equals("*")) return true;
        if (pattern.endsWith(".*")) return type.startsWith(pattern.substring(0, pattern.length() - 1));
        return pattern.equals(type);
    }

    private EventTypes() {
    }
}
