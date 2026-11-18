package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.ImageResizeSpec;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Runs deterministic image transforms and optional Depth Anything V2 inference without network I/O. */
@Component
public final class LocalImageProcessorAdapter implements MediaAdapter {
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final LocalDepthInference depth;

    public LocalImageProcessorAdapter(ArtifactService artifacts, AssetService assets,
            LocalDepthInference depth) {
        this.artifacts = artifacts;
        this.assets = assets;
        this.depth = depth;
    }

    @Override
    public String adapterId() {
        return MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR;
    }

    @Override
    public String preflightFailure(AttemptContext context) {
        try {
            JsonNode operation = operation(context.lease());
            String name = operation.path("name").asText();
            BufferedImage image = source(context, operation);
            if ("RESIZE".equals(name))
                ImageResizeSpec.parse(operation.path("parameters"))
                        .dimensions(image.getWidth(), image.getHeight());
            if ("DEPTH_MAP".equals(name) && !depth.available())
                return "LOCAL_DEPTH_MODEL_UNAVAILABLE";
            validateParameters(name, operation.path("parameters"));
            return null;
        } catch (RuntimeException invalid) {
            return "PROVIDER_UNSUPPORTED_INPUT";
        }
    }

    @Override
    public Submission submit(AttemptContext context) {
        try {
            JsonNode operation = operation(context.lease());
            BufferedImage source = source(context, operation);
            BufferedImage output = process(source, operation.path("name").asText(),
                    operation.path("parameters"));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(output, "png", bytes)) {
                return new Submission.Rejected("LOCAL_IMAGE_ENCODING_FAILED");
            }
            return new Submission.Completed(new MediaPayload(
                    new ByteArrayInputStream(bytes.toByteArray()), "image/png"));
        } catch (RuntimeException | IOException failure) {
            return new Submission.Rejected("LOCAL_IMAGE_PROCESSING_FAILED");
        }
    }

    @Override
    public Submission reconcile(AttemptContext context) {
        return new Submission.Blocked("LOCAL_IMAGE_HAS_NO_EXTERNAL_REQUEST");
    }

    private JsonNode operation(Task task) {
        JsonNode operation = task.input().path("imageOperation");
        if (!operation.isObject() || operation.path("name").asText().isBlank()) {
            throw new IllegalArgumentException("Local task lacks an image operation");
        }
        return operation;
    }

    private BufferedImage source(AttemptContext context, JsonNode operation) {
        UUID versionId = UUID.fromString(operation.path("sourceVersionId").asText());
        ArtifactVersion version = artifacts.requireImageVersionForTask(context.ownerId(),
                context.lease().projectId(), versionId);
        UUID assetId = UUID.fromString(version.content().path("assetId").asText());
        Path file = assets.get(context.ownerId(), context.lease().projectId(), assetId).path();
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null)
                throw new IllegalArgumentException("Source image cannot be decoded");
            return image;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot decode source image", failure);
        }
    }

    private BufferedImage process(BufferedImage source, String name, JsonNode parameters) {
        validateParameters(name, parameters);
        return switch (name) {
            case "DEPTH_MAP" -> depth.depth(source);
            case "UPSCALE" -> upscale(source, parameters.path("scale").asInt());
            case "RESIZE" -> {
                var dimensions = ImageResizeSpec.parse(parameters).dimensions(source.getWidth(), source.getHeight());
                yield LocalRaster.upscaleTo(source, dimensions.width(), dimensions.height());
            }
            case "CROP" -> crop(source, parameters);
            case "ROTATE" -> rotate(source, parameters.path("quarterTurns").asInt());
            case "FLIP_HORIZONTAL" -> flip(source, true);
            case "FLIP_VERTICAL" -> flip(source, false);
            default -> throw new IllegalArgumentException("Operation is not local");
        };
    }

    private void validateParameters(String name, JsonNode parameters) {
        switch (name) {
            case "DEPTH_MAP", "FLIP_HORIZONTAL", "FLIP_VERTICAL" -> {
            }
            case "RESIZE" -> ImageResizeSpec.parse(parameters);
            case "UPSCALE" -> {
                int scale = parameters.path("scale").asInt();
                if (scale != 2 && scale != 4)
                    throw new IllegalArgumentException("Invalid scale");
            }
            case "CROP" -> {
                double x = parameters.path("x").asDouble(-1);
                double y = parameters.path("y").asDouble(-1);
                double width = parameters.path("width").asDouble(-1);
                double height = parameters.path("height").asDouble(-1);
                if (x < 0 || y < 0 || width <= 0 || height <= 0
                        || x + width > 1.000001 || y + height > 1.000001) {
                    throw new IllegalArgumentException("Invalid crop bounds");
                }
            }
            case "ROTATE" -> {
                int turns = parameters.path("quarterTurns").asInt();
                if (turns < 1 || turns > 3)
                    throw new IllegalArgumentException("Invalid rotation");
            }
            default -> throw new IllegalArgumentException("Unsupported local operation");
        }
    }

    private BufferedImage upscale(BufferedImage source, int scale) {
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

    private BufferedImage crop(BufferedImage source, JsonNode parameters) {
        int x = (int) Math.floor(parameters.path("x").asDouble() * source.getWidth());
        int y = (int) Math.floor(parameters.path("y").asDouble() * source.getHeight());
        int width = Math.max(1, (int) Math.round(parameters.path("width").asDouble()
                * source.getWidth()));
        int height = Math.max(1, (int) Math.round(parameters.path("height").asDouble()
                * source.getHeight()));
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

    private BufferedImage rotate(BufferedImage source, int quarterTurns) {
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

    private BufferedImage flip(BufferedImage source, boolean horizontal) {
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
