package dev.cdb.core.visual;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Pixel-based image comparison with a per-pixel colour tolerance. */
public final class ImageDiff {
    public record Result(long diffPixels, long totalPixels, boolean sizeMismatch, byte[] diffPng) {
        public double ratio() {
            return totalPixels == 0 ? 0 : (double) diffPixels / totalPixels;
        }
    }

    private ImageDiff() {
    }

    /**
     * @param pixelThreshold 0..1, maximum per-channel difference (fraction of 255) still considered equal
     */
    public static Result compare(byte[] baseline, byte[] candidate, double pixelThreshold) throws IOException {
        BufferedImage a = read(baseline);
        BufferedImage b = read(candidate);
        int w = Math.max(a.getWidth(), b.getWidth());
        int h = Math.max(a.getHeight(), b.getHeight());
        boolean sizeMismatch = a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight();
        int tolerance = (int) Math.round(Math.max(0, Math.min(1, pixelThreshold)) * 255);
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        long diffCount = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean inA = x < a.getWidth() && y < a.getHeight();
                boolean inB = x < b.getWidth() && y < b.getHeight();
                if (!inA || !inB) {
                    diff.setRGB(x, y, 0xFF00FF);
                    diffCount++;
                    continue;
                }
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                if (channelDistance(pa, pb) > tolerance) {
                    diff.setRGB(x, y, 0xFF0000);
                    diffCount++;
                } else {
                    int gray = (((pa >> 16) & 0xFF) + ((pa >> 8) & 0xFF) + (pa & 0xFF)) / 3;
                    int faded = 200 + gray * 55 / 255;
                    diff.setRGB(x, y, (faded << 16) | (faded << 8) | faded);
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(diff, "png", out);
        return new Result(diffCount, (long) w * h, sizeMismatch, out.toByteArray());
    }

    static int channelDistance(int p, int q) {
        int dr = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
        int dg = Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF));
        int db = Math.abs((p & 0xFF) - (q & 0xFF));
        return Math.max(dr, Math.max(dg, db));
    }

    public static BufferedImage read(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw new IOException("Unsupported image format");
        return img;
    }
}
