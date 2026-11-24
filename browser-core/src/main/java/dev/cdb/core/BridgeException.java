package dev.cdb.core;

import dev.cdb.protocol.ErrorCode;

/** Domain exception mapped to an RFC 9457 problem response by the driver. */
public class BridgeException extends RuntimeException {
    private final ErrorCode code;

    public BridgeException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public BridgeException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static BridgeException validation(String message) {
        return new BridgeException(ErrorCode.VALIDATION_FAILED, message);
    }
}
