package dev.cdb.protocol;

public enum ConsoleLevel {
    DEBUG, INFO, WARNING, ERROR;

    /** Maps a browser console type (log, info, warning, error, debug, trace, ...) to a level. */
    public static ConsoleLevel fromConsoleType(String type) {
        if (type == null) return INFO;
        return switch (type.toLowerCase()) {
            case "error", "assert" -> ERROR;
            case "warning", "warn" -> WARNING;
            case "debug", "trace", "verbose" -> DEBUG;
            default -> INFO;
        };
    }
}
