package dev.cdb.protocol;

import java.time.Instant;

public record BaselineInfo(String name, int width, int height, Instant createdAt) {
}
