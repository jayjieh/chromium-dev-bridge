package dev.cdb.client;

/** Error returned by the driver (RFC 9457 problem) or a transport failure (status 0). */
public class DriverApiException extends RuntimeException {
    private final int status;
    private final String code;

    public DriverApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public DriverApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.code = "TRANSPORT";
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
