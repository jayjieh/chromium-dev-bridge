package dev.cdb.protocol;

/** Machine-readable error codes carried in RFC 9457 problem responses ("code" member). */
public enum ErrorCode {
    SESSION_NOT_FOUND(404), SESSION_NOT_RUNNING(409), ACCESS_DENIED(403), UNAUTHENTICATED(401),
    URL_NOT_ALLOWED(403), APPROVAL_REQUIRED(403), VALIDATION_FAILED(400), TIMEOUT(504),
    ELEMENT_NOT_FOUND(404), BROWSER_ERROR(502), RATE_LIMITED(429), PAYLOAD_TOO_LARGE(413),
    NOT_FOUND(404), UNSUPPORTED(501), LIMIT_EXCEEDED(409), INTERNAL(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
