package dev.agenvas.provider.application;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Shared allocation limit and bicubic sampling for local image operations and depth maps. */
final class LocalRaster {
    private static final long MAX_OUTPUT_PIXELS = 40_000_000L;

    private LocalRaster() {
    }

    static BufferedImage upscaleTo(BufferedImage source, int width, int height) {
        BufferedImage output = compatible(source, width, height);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    static BufferedImage compatible(BufferedImage source, int width, int height) {
        requirePixelLimit(width, height);
        return new BufferedImage(width, height, source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB);
    }

    private static void requirePixelLimit(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > MAX_OUTPUT_PIXELS) {
            throw new IllegalArgumentException("Processed image exceeds the pixel limit");
        }
    }
}
