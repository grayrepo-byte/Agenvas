package dev.agenvas.provider.application;

import dev.agenvas.task.domain.ImageOperation;
import dev.agenvas.task.domain.ImageResizeSpec;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import tools.jackson.databind.JsonNode;

/** Prepares the compiled transforms supported for frozen local image tasks. */
final class LocalImageOperations {
    private LocalImageOperations() {
    }

    @FunctionalInterface
    interface Prepared {
        BufferedImage execute();

        default String preflightFailure() {
            return null;
        }
    }

    /** Decode and validate without creating an output raster or running model inference. */
    static Prepared prepare(BufferedImage source, ImageOperation operation, JsonNode parameters,
            LocalDepthInference depth) {
        return switch (operation) {
            case DEPTH_MAP -> new DepthMap(source, depth);
            case UPSCALE -> {
                int scale = parameters.path("scale").asInt();
                if (scale != 2 && scale != 4)
                    throw new IllegalArgumentException("Invalid scale");
                yield () -> upscale(source, scale);
            }
            case RESIZE -> {
                var dimensions = ImageResizeSpec.parse(parameters).dimensions(source.getWidth(), source.getHeight());
                yield () -> LocalRaster.upscaleTo(source, dimensions.width(), dimensions.height());
            }
            case CROP -> {
                double left = parameters.path("x").asDouble(-1);
                double top = parameters.path("y").asDouble(-1);
                double width = parameters.path("width").asDouble(-1);
                double height = parameters.path("height").asDouble(-1);
                if (left < 0 || top < 0 || width <= 0 || height <= 0
                        || left + width > 1.000001 || top + height > 1.000001) {
                    throw new IllegalArgumentException("Invalid crop bounds");
                }
                yield () -> crop(source, left, top, width, height);
            }
            case ROTATE -> {
                int turns = parameters.path("quarterTurns").asInt();
                if (turns < 1 || turns > 3)
                    throw new IllegalArgumentException("Invalid rotation");
                yield () -> rotate(source, turns);
            }
            case FLIP_HORIZONTAL -> () -> flip(source, true);
            case FLIP_VERTICAL -> () -> flip(source, false);
            default -> throw new IllegalArgumentException("Unsupported local operation");
        };
    }

    private record DepthMap(BufferedImage source, LocalDepthInference depth) implements Prepared {
        @Override
        public BufferedImage execute() {
            return depth.depth(source);
        }

        @Override
        public String preflightFailure() {
            return depth.available() ? null : "LOCAL_DEPTH_MODEL_UNAVAILABLE";
        }
    }

    private static BufferedImage upscale(BufferedImage source, int scale) {
        int width = Math.multiplyExact(source.getWidth(), scale);
        int height = Math.multiplyExact(source.getHeight(), scale);
        BufferedImage output = LocalRaster.compatible(source, width, height);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static BufferedImage crop(BufferedImage source, double left, double top,
            double normalizedWidth, double normalizedHeight) {
        int x = (int) Math.floor(left * source.getWidth());
        int y = (int) Math.floor(top * source.getHeight());
        int width = Math.max(1, (int) Math.round(normalizedWidth * source.getWidth()));
        int height = Math.max(1, (int) Math.round(normalizedHeight * source.getHeight()));
        width = Math.min(width, source.getWidth() - x);
        height = Math.min(height, source.getHeight() - y);
        BufferedImage output = LocalRaster.compatible(source, width, height);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, width, height, x, y, x + width, y + height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static BufferedImage rotate(BufferedImage source, int quarterTurns) {
        boolean swap = quarterTurns % 2 == 1;
        int width = swap ? source.getHeight() : source.getWidth();
        int height = swap ? source.getWidth() : source.getHeight();
        BufferedImage output = LocalRaster.compatible(source, width, height);
        Graphics2D graphics = output.createGraphics();
        try {
            AffineTransform transform = new AffineTransform();
            switch (quarterTurns) {
                case 1 -> {
                    transform.translate(width, 0);
                    transform.rotate(Math.PI / 2);
                }
                case 2 -> {
                    transform.translate(width, height);
                    transform.rotate(Math.PI);
                }
                case 3 -> {
                    transform.translate(0, height);
                    transform.rotate(-Math.PI / 2);
                }
                default -> throw new IllegalArgumentException("Invalid rotation");
            }
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static BufferedImage flip(BufferedImage source, boolean horizontal) {
        BufferedImage output = LocalRaster.compatible(source, source.getWidth(), source.getHeight());
        Graphics2D graphics = output.createGraphics();
        try {
            if (horizontal) {
                graphics.drawImage(source, source.getWidth(), 0, -source.getWidth(),
                        source.getHeight(), null);
            } else {
                graphics.drawImage(source, 0, source.getHeight(), source.getWidth(),
                        -source.getHeight(), null);
            }
        } finally {
            graphics.dispose();
        }
        return output;
    }
}
