package dev.cdb.protocol;

import java.time.Instant;

public record ScreenshotInfo(String screenshotId, String sessionId, String name, ImageFormat format, int width, int height,
                             long sizeBytes, Instant createdAt, String pageUrl, String imageUrl, String base64) {
    public ScreenshotInfo withBase64(String b) {
        return new ScreenshotInfo(screenshotId, sessionId, name, format, width, height, sizeBytes, createdAt, pageUrl, imageUrl, b);
    }
}
