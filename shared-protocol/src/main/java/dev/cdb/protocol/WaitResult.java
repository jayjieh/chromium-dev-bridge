package dev.cdb.protocol;

public record WaitResult(boolean satisfied, long durationMs, String message) {
}
