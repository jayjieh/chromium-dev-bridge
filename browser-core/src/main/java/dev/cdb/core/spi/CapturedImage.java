package dev.cdb.core.spi;

/** Raw screenshot bytes returned by a provider. */
public record CapturedImage(byte[] bytes, dev.cdb.protocol.ImageFormat format, String pageUrl) {
}
