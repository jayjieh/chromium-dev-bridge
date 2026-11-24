package dev.cdb.protocol;

public record StepResult(int index, String action, String description, boolean success, long durationMs, String message,
                         String screenshotId) {
}
