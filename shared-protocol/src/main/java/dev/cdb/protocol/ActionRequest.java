package dev.cdb.protocol;

import java.util.List;

public record ActionRequest(ActionType type, String tabId, Locator locator, Locator target, String value,
                            List<String> values, String key, String attribute, Integer deltaX, Integer deltaY,
                            Double x, Double y, Integer timeoutMs) {

    public static ActionRequest of(ActionType type, Locator locator) {
        return new ActionRequest(type, null, locator, null, null, null, null, null, null, null, null, null, null);
    }

    public static ActionRequest fill(Locator locator, String value) {
        return new ActionRequest(ActionType.FILL, null, locator, null, value, null, null, null, null, null, null, null, null);
    }

    public static ActionRequest press(Locator locator, String key) {
        return new ActionRequest(ActionType.PRESS, null, locator, null, null, null, key, null, null, null, null, null, null);
    }

    public static ActionRequest select(Locator locator, List<String> values) {
        return new ActionRequest(ActionType.SELECT, null, locator, null, null, values, null, null, null, null, null, null, null);
    }

    public static ActionRequest scroll(Locator locator, int dx, int dy) {
        return new ActionRequest(ActionType.SCROLL, null, locator, null, null, null, null, null, dx, dy, null, null, null);
    }

    public static ActionRequest attribute(Locator locator, String attribute) {
        return new ActionRequest(ActionType.GET_ATTRIBUTE, null, locator, null, null, null, null, attribute, null, null, null, null, null);
    }

    public ActionRequest withTab(String tab) {
        return new ActionRequest(type, tab, locator, target, value, values, key, attribute, deltaX, deltaY, x, y, timeoutMs);
    }

    public ActionRequest withTimeout(Integer t) {
        return new ActionRequest(type, tabId, locator, target, value, values, key, attribute, deltaX, deltaY, x, y, t);
    }
}
