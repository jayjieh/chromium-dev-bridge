package dev.cdb.protocol;

public record CompareResult(boolean match, long diffPixels, long totalPixels, double diffRatio, boolean sizeMismatch,
                            String diffScreenshotId, String message) {
}
