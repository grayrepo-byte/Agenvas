package dev.agenvas.provider.infrastructure;

import dev.agenvas.project.domain.Project;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Fixed ComfyUI input canvases preserve aspect ratio and flatten transparency over gray. */
public final class ComfyUiInputImage {
    private static final int IMAGE_LONG_EDGE = 1024;
    private static final int IMAGE_SHORT_EDGE = 576;
    private static final int IMAGE_SQUARE_EDGE = 768;
    private static final Color BACKGROUND = new Color(127, 127, 127);
    private static final String FORMAT = "png";

    private ComfyUiInputImage() {}

    public static Dimensions imageDimensions(Project.AspectRatio ratio) {
        return switch (ratio) {
            case LANDSCAPE_16_9 -> new Dimensions(IMAGE_LONG_EDGE, IMAGE_SHORT_EDGE);
            case PORTRAIT_9_16 -> new Dimensions(IMAGE_SHORT_EDGE, IMAGE_LONG_EDGE);
            case SQUARE_1_1 -> new Dimensions(IMAGE_SQUARE_EDGE, IMAGE_SQUARE_EDGE);
        };
    }

    /** A null source produces the neutral canvas used for text-to-image generation. */
    public static byte[] png(BufferedImage source, int width, int height) throws IOException {
        BufferedImage normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setColor(BACKGROUND);
            graphics.fillRect(0, 0, width, height);
            if (source != null) {
                double scale = Math.min((double) width / source.getWidth(),
                        (double) height / source.getHeight());
                int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, (width - drawWidth) / 2,
                        (height - drawHeight) / 2, drawWidth, drawHeight, null);
            }
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(normalized, FORMAT, output)) {
                throw new IllegalStateException("PNG encoder unavailable");
            }
            return output.toByteArray();
        }
    }

    public record Dimensions(int width, int height) {}
}
