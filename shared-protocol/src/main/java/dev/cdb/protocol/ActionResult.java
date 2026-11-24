package dev.cdb.protocol;

public record ActionResult(boolean success, ActionType type, long durationMs, Object value, String message,
                           String pageUrl, boolean navigated, String errorCode) {

    public static ActionResult ok(ActionType type, long durationMs, Object value, String pageUrl, boolean navigated) {
        return new ActionResult(true, type, durationMs, value, null, pageUrl, navigated, null);
    }

    public static ActionResult failed(ActionType type, long durationMs, ErrorCode code, String message, String pageUrl) {
        return new ActionResult(false, type, durationMs, null, message, pageUrl, false, code.name());
    }
}
