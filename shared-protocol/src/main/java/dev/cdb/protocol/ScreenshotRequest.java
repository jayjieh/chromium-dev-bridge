package dev.cdb.protocol;

public record ScreenshotRequest(String tabId, String name, Boolean fullPage, Locator locator, ImageFormat format,
                                Integer quality, ViewportPreset viewportPreset, Boolean maskSensitive, Boolean inline) {
    public static ScreenshotRequest viewport(String name) {
        return new ScreenshotRequest(null, name, false, null, ImageFormat.PNG, null, null, true, false);
    }
}
