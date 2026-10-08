package io.github.red171.libtray;

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
            int sourceWidth = image.getWidth();
            int sourceHeight = image.getHeight();
            double scale = (double) maxSize / Math.max(sourceWidth, sourceHeight);
            int width = Math.max(1, (int) Math.round(sourceWidth * scale));
            int height = Math.max(1, (int) Math.round(sourceHeight * scale));
            int[] source = new int[sourceWidth * sourceHeight];
            image.getRGB(0, 0, sourceWidth, sourceHeight, source, 0, sourceWidth);
            int[] scaled = areaAverage(source, sourceWidth, sourceHeight, width, height);
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            result.setRGB(0, 0, width, height, scaled, 0, width);
            var encoded = new ByteArrayOutputStream();
            return ImageIO.write(result, "PNG", encoded) ? encoded.toByteArray() : bytes;
        } catch (IOException | RuntimeException failure) {
            return bytes;
        }
    }

    /**
     * Shrinks ARGB pixels by area averaging: each target pixel is the mean of
     * the source area it covers, partly covered pixels weighted by coverage.
     * Colour is averaged premultiplied by alpha so transparent pixels cannot
     * bleed into the edge. Plain array arithmetic, no AWT toolkit or Java2D.
     */
    static int[] areaAverage(int[] source, int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        int[] out = new int[targetWidth * targetHeight];
        double stepX = (double) sourceWidth / targetWidth;
        double stepY = (double) sourceHeight / targetHeight;
        for (int ty = 0; ty < targetHeight; ty++) {
            double top = ty * stepY;
            double bottom = (ty + 1) * stepY;
            for (int tx = 0; tx < targetWidth; tx++) {
                double left = tx * stepX;
                double right = (tx + 1) * stepX;
                double area = 0;
                double alpha = 0;
                double red = 0;
                double green = 0;
                double blue = 0;
                for (int y = (int) top; y < bottom && y < sourceHeight; y++) {
                    double coverY = Math.min(y + 1.0, bottom) - Math.max(y, top);
                    for (int x = (int) left; x < right && x < sourceWidth; x++) {
                        double cover = (Math.min(x + 1.0, right) - Math.max(x, left)) * coverY;
                        int pixel = source[y * sourceWidth + x];
                        double weight = cover * (pixel >>> 24 & 0xff);
                        area += cover;
                        alpha += weight;
                        red += weight * (pixel >>> 16 & 0xff);
                        green += weight * (pixel >>> 8 & 0xff);
                        blue += weight * (pixel & 0xff);
                    }
                }
                out[ty * targetWidth + tx] = alpha <= 0 ? 0
                        : channel(alpha / area) << 24 | channel(red / alpha) << 16
                        | channel(green / alpha) << 8 | channel(blue / alpha);
            }
        }
        return out;
    }

    private static int channel(double value) {
        return Math.max(0, Math.min(255, (int) (value + 0.5)));
    }
}
