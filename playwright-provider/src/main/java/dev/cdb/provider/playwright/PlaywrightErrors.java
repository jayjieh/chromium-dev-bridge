package dev.cdb.provider.playwright;

import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import dev.cdb.core.BridgeException;
import dev.cdb.protocol.ErrorCode;

/** Maps Playwright exceptions to bridge error codes with short, readable messages. */
final class PlaywrightErrors {
    private PlaywrightErrors() {
    }

    static BridgeException translate(RuntimeException e, String context) {
        if (e instanceof BridgeException be) return be;
        String msg = firstLine(e);
        if (e instanceof TimeoutError) return new BridgeException(ErrorCode.TIMEOUT, context + ": " + msg, e);
        if (msg.contains("ERR_BLOCKED_BY_CLIENT")) {
            return new BridgeException(ErrorCode.URL_NOT_ALLOWED, context + ": navigation blocked by URL policy", e);
        }
        if (msg.contains("strict mode violation")) {
            return new BridgeException(ErrorCode.VALIDATION_FAILED, context + ": locator matched several elements; "
                    + "use a more specific locator or nth. " + msg, e);
        }
        if (msg.contains("Target page, context or browser has been closed") || msg.contains("Browser has been closed")
                || msg.contains("Target closed")) {
            return new BridgeException(ErrorCode.SESSION_NOT_RUNNING, context + ": browser or page was closed", e);
        }
        if (msg.contains("net::ERR_")) return new BridgeException(ErrorCode.BROWSER_ERROR, context + ": " + msg, e);
        if (e instanceof PlaywrightException) return new BridgeException(ErrorCode.BROWSER_ERROR, context + ": " + msg, e);
        return new BridgeException(ErrorCode.INTERNAL, context + ": " + msg, e);
    }

    static String firstLine(Throwable e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        // Playwright messages contain call logs after "=====" / "Call log:"; keep the useful part.
        int cut = m.indexOf("\n=====");
        if (cut < 0) cut = m.indexOf("\nCall log:");
        if (cut > 0) m = m.substring(0, cut);
        m = m.replace("Error {\n  message='", "").trim();
        String[] lines = m.split("\\r?\\n");
        String first = lines[0].trim();
        if (first.length() < 20 && lines.length > 1) first = first + " " + lines[1].trim();
        return first.length() > 500 ? first.substring(0, 500) + "..." : first;
    }
}
