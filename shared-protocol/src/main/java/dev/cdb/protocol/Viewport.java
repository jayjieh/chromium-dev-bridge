package dev.cdb.protocol;

public record Viewport(int width, int height) {
    public static final int MIN = 200;

    public Viewport {
        if (width < MIN || height < MIN || width > 7680 || height > 4320) {
            throw new IllegalArgumentException("Viewport must be between 200x200 and 7680x4320, got " + width + "x" + height);
        }
    }
}
