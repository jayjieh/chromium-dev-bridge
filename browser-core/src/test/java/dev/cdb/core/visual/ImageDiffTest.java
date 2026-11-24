package dev.cdb.core.visual;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cdb.core.FakeProvider;
import java.awt.Color;
import org.junit.jupiter.api.Test;

class ImageDiffTest {
    @Test
    void identicalImagesMatch() throws Exception {
        byte[] a = FakeProvider.png(10, 10, Color.WHITE);
        ImageDiff.Result r = ImageDiff.compare(a, a, 0.1);
        assertThat(r.diffPixels()).isZero();
        assertThat(r.sizeMismatch()).isFalse();
    }

    @Test
    void toleranceAbsorbsSmallColourChanges() throws Exception {
        byte[] a = FakeProvider.png(10, 10, new Color(200, 200, 200));
        byte[] b = FakeProvider.png(10, 10, new Color(205, 200, 200));
        assertThat(ImageDiff.compare(a, b, 0.1).diffPixels()).isZero();
        assertThat(ImageDiff.compare(a, b, 0.0).diffPixels()).isEqualTo(100);
    }

    @Test
    void sizeMismatchCountsExtraPixels() throws Exception {
        ImageDiff.Result r = ImageDiff.compare(FakeProvider.png(10, 10, Color.WHITE), FakeProvider.png(10, 20, Color.WHITE), 0.1);
        assertThat(r.sizeMismatch()).isTrue();
        assertThat(r.diffPixels()).isEqualTo(100);
        assertThat(r.totalPixels()).isEqualTo(200);
    }
}
