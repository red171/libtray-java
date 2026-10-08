package io.github.red171.libtray;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IconScalingTest {
    @Test
    void leavesSmallImagesAndDisabledScalingUnchanged() throws Exception {
        byte[] small = png(32, 16);
        assertSame(small, IconScaling.fit(small, 256));
        byte[] large = png(1024, 512);
        assertSame(large, IconScaling.fit(large, null));
    }

    @Test
    void preservesPixelsAndAspectRatio() throws Exception {
        byte[] original = png(1024, 512);
        byte[] fitted = IconScaling.fit(original, 256);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(fitted));
        assertEquals(256, image.getWidth());
        assertEquals(128, image.getHeight());
        assertEquals(0xffff3300, image.getRGB(128, 64));
        assertEquals(0, image.getRGB(0, 0));
        assertTrue(fitted.length < original.length);
    }

    @Test
    void handlesPortraitAndVeryThinImages() throws Exception {
        BufferedImage portrait = ImageIO.read(new ByteArrayInputStream(IconScaling.fit(png(512, 1024), 256)));
        assertEquals(128, portrait.getWidth());
        assertEquals(256, portrait.getHeight());
        BufferedImage thin = ImageIO.read(new ByteArrayInputStream(IconScaling.fit(png(1, 1024), 16)));
        assertEquals(1, thin.getWidth());
        assertEquals(16, thin.getHeight());
    }

    @Test
    void preservesUndecodableData() {
        byte[] invalid = {1, 2, 3};
        assertSame(invalid, IconScaling.fit(invalid, 256));
        byte[] empty = new byte[0];
        assertSame(empty, IconScaling.fit(empty, 256));
    }

    @Test
    void rejectsInvalidScale() {
        assertThrows(IllegalArgumentException.class, () -> IconScaling.fit(new byte[]{1}, 0));
    }

    private byte[] png(int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(0xffff3300, true));
            graphics.fillRect(width / 4, height / 4, Math.max(1, width / 2), Math.max(1, height / 2));
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "PNG", output));
        return output.toByteArray();
    }

    @Test
    void areaAverageKeepsAlphaAndIgnoresTransparentColour() {
        int[] source = {0xffff0000, 0x0000ff00, 0xffff0000, 0x00000000};
        int[] scaled = IconScaling.areaAverage(source, 2, 2, 1, 1);
        assertEquals(0x80ff0000, scaled[0]);
        assertEquals(0, IconScaling.areaAverage(new int[4], 2, 2, 1, 1)[0]);
    }
}
