package io.github.red171.libtray;

import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

public final class IconScaling {
    private IconScaling() {
    }

    public static byte[] fit(byte[] bytes, Integer maxSize) {
        if (maxSize == null) {
            return bytes;
        }
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive or null");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || (image.getWidth() <= maxSize && image.getHeight() <= maxSize)) {
                return bytes;
            }
            double scale = (double) maxSize / Math.max(image.getWidth(), image.getHeight());
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            Image scaled = image.getScaledInstance(width, height, Image.SCALE_SMOOTH);
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            var graphics = result.createGraphics();
            try {
                if (!graphics.drawImage(scaled, 0, 0, null)) {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(image, 0, 0, width, height, null);
                }
            } finally {
                graphics.dispose();
                scaled.flush();
            }
            var encoded = new ByteArrayOutputStream();
            return ImageIO.write(result, "PNG", encoded) ? encoded.toByteArray() : bytes;
        } catch (IOException | RuntimeException failure) {
            return bytes;
        }
    }
}
