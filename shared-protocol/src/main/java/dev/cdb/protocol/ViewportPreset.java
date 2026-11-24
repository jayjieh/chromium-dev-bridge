package dev.cdb.protocol;

/** Named viewport sizes for responsive testing. */
public enum ViewportPreset {
    DESKTOP(1920, 1080), LAPTOP(1366, 768), TABLET(768, 1024), MOBILE(390, 844);

    private final int width;
    private final int height;

    ViewportPreset(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public Viewport viewport() {
        return new Viewport(width, height);
    }
}
