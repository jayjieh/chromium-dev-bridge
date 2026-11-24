package dev.cdb.protocol;

public record ViewportRequest(ViewportPreset preset, Integer width, Integer height) {
    public Viewport resolve() {
        if (preset != null) {
            return preset.viewport();
        }
        if (width == null || height == null) {
            throw new IllegalArgumentException("Either preset or width and height are required");
        }
        return new Viewport(width, height);
    }
}
