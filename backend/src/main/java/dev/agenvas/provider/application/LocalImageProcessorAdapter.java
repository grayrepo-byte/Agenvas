package dev.agenvas.provider.application;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.domain.Task;
import jakarta.annotation.PreDestroy;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Runs deterministic image transforms and optional Depth Anything V2 inference without network I/O. */
@Component
public final class LocalImageProcessorAdapter implements MediaAdapter {
    private static final int DEFAULT_DEPTH_EDGE = 518;
    private static final long MAX_OUTPUT_PIXELS = 40_000_000L;
    private static final float[] IMAGENET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] IMAGENET_STD = {0.229f, 0.224f, 0.225f};

    private final ArtifactService artifacts;
    private final AssetService assets;
    private final LocalImageProperties properties;
    private OrtSession depthSession;

    public LocalImageProcessorAdapter(ArtifactService artifacts, AssetService assets,
            LocalImageProperties properties) {
        this.artifacts = artifacts;
        this.assets = assets;
        this.properties = properties;
    }

    @Override public String adapterId() { return MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR; }

    @Override public boolean supports(PortInput input) {
        return input.kind() == Task.Kind.IMAGE_GENERATION;
    }

    @Override public String preflightFailure(AttemptContext context) {
        try {
            JsonNode operation = operation(context.lease());
            String name = operation.path("name").asText();
            source(context, operation);
            if ("DEPTH_MAP".equals(name)) {
                Path model = properties.depthModelPath();
                if (model == null || !Files.isRegularFile(model)) {
                    return "LOCAL_DEPTH_MODEL_UNAVAILABLE";
                }
            }
            validateParameters(name, operation.path("parameters"));
            return null;
        } catch (RuntimeException invalid) {
            return "PROVIDER_UNSUPPORTED_INPUT";
        }
    }

    @Override public Submission submit(AttemptContext context) {
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

    @Override public Submission reconcile(AttemptContext context) {
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
            if (image == null) throw new IllegalArgumentException("Source image cannot be decoded");
            return image;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot decode source image", failure);
        }
    }

    private BufferedImage process(BufferedImage source, String name, JsonNode parameters) {
        validateParameters(name, parameters);
        return switch (name) {
            case "DEPTH_MAP" -> depth(source);
            case "UPSCALE" -> upscale(source, parameters.path("scale").asInt());
            case "CROP" -> crop(source, parameters);
            case "ROTATE" -> rotate(source, parameters.path("quarterTurns").asInt());
            case "FLIP_HORIZONTAL" -> flip(source, true);
            case "FLIP_VERTICAL" -> flip(source, false);
            default -> throw new IllegalArgumentException("Operation is not local");
        };
    }

    private void validateParameters(String name, JsonNode parameters) {
        switch (name) {
            case "DEPTH_MAP", "FLIP_HORIZONTAL", "FLIP_VERTICAL" -> { }
            case "UPSCALE" -> {
                int scale = parameters.path("scale").asInt();
                if (scale != 2 && scale != 4) throw new IllegalArgumentException("Invalid scale");
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
                if (turns < 1 || turns > 3) throw new IllegalArgumentException("Invalid rotation");
            }
            default -> throw new IllegalArgumentException("Unsupported local operation");
        }
    }

    private BufferedImage upscale(BufferedImage source, int scale) {
        int width = Math.multiplyExact(source.getWidth(), scale);
        int height = Math.multiplyExact(source.getHeight(), scale);
        requirePixelLimit(width, height);
        BufferedImage output = compatible(source, width, height);
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
        BufferedImage output = compatible(source, width, height);
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
        BufferedImage output = compatible(source, width, height);
        Graphics2D graphics = output.createGraphics();
        try {
            AffineTransform transform = new AffineTransform();
            switch (quarterTurns) {
                case 1 -> { transform.translate(width, 0); transform.rotate(Math.PI / 2); }
                case 2 -> { transform.translate(width, height); transform.rotate(Math.PI); }
                case 3 -> { transform.translate(0, height); transform.rotate(-Math.PI / 2); }
                default -> throw new IllegalArgumentException("Invalid rotation");
            }
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private BufferedImage flip(BufferedImage source, boolean horizontal) {
        BufferedImage output = compatible(source, source.getWidth(), source.getHeight());
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

    /** Shared genuine depth inference for archived image and video-frame processing. */
    BufferedImage depth(BufferedImage source) {
        OrtSession session = depthSession();
        String inputName = session.getInputNames().iterator().next();
        NodeInfo node;
        try {
            node = session.getInputInfo().get(inputName);
        } catch (OrtException failure) {
            throw new IllegalStateException("Cannot inspect depth model input", failure);
        }
        if (!(node.getInfo() instanceof TensorInfo info) || info.getShape().length != 4) {
            throw new IllegalStateException("Depth model input must be NCHW");
        }
        long[] shape = info.getShape();
        int height = shape[2] > 0 ? Math.toIntExact(shape[2]) : DEFAULT_DEPTH_EDGE;
        int width = shape[3] > 0 ? Math.toIntExact(shape[3]) : DEFAULT_DEPTH_EDGE;
        BufferedImage resized = upscaleTo(source, width, height);
        FloatBuffer values = FloatBuffer.allocate(3 * width * height);
        for (int channel = 0; channel < 3; channel++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = resized.getRGB(x, y);
                    int component = switch (channel) {
                        case 0 -> rgb >> 16 & 0xff;
                        case 1 -> rgb >> 8 & 0xff;
                        default -> rgb & 0xff;
                    };
                    values.put(((component / 255f) - IMAGENET_MEAN[channel])
                            / IMAGENET_STD[channel]);
                }
            }
        }
        values.flip();
        try (OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),
                values, new long[]{1, 3, height, width});
                OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
            float[][] depth = flattenDepth(result.get(0).getValue(), height, width);
            BufferedImage map = normalizeDepth(depth, width, height);
            return upscaleTo(map, source.getWidth(), source.getHeight());
        } catch (OrtException failure) {
            throw new IllegalStateException("Depth model inference failed", failure);
        }
    }

    boolean depthModelAvailable() {
        Path model = properties.depthModelPath();
        return model != null && Files.isRegularFile(model) && Files.isReadable(model);
    }

    private synchronized OrtSession depthSession() {
        if (depthSession != null) return depthSession;
        Path model = properties.depthModelPath();
        if (model == null || !Files.isRegularFile(model)) {
            throw new IllegalStateException("Depth model is not configured");
        }
        try {
            depthSession = OrtEnvironment.getEnvironment().createSession(model.toString(),
                    new OrtSession.SessionOptions());
            if (depthSession.getInputNames().size() != 1 || depthSession.getOutputNames().isEmpty()) {
                throw new IllegalStateException("Depth model must expose one input and an output");
            }
            return depthSession;
        } catch (OrtException failure) {
            throw new IllegalStateException("Cannot load depth model", failure);
        }
    }

    private float[][] flattenDepth(Object value, int height, int width) {
        if (value instanceof float[][][] batch && batch.length == 1) return batch[0];
        if (value instanceof float[][][][] batch && batch.length == 1
                && batch[0].length == 1) return batch[0][0];
        if (value instanceof float[][] plane) return plane;
        throw new IllegalStateException("Unsupported depth model output");
    }

    private BufferedImage normalizeDepth(float[][] values, int width, int height) {
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        for (float[] row : values) for (float value : row) {
            if (Float.isFinite(value)) {
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
            }
        }
        if (!Float.isFinite(minimum) || maximum <= minimum) {
            throw new IllegalStateException("Depth model produced no usable range");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        float range = maximum - minimum;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int gray = Math.clamp(Math.round((values[y][x] - minimum) / range * 255f), 0, 255);
            image.getRaster().setSample(x, y, 0, gray);
        }
        return image;
    }

    private BufferedImage upscaleTo(BufferedImage source, int width, int height) {
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

    private BufferedImage compatible(BufferedImage source, int width, int height) {
        requirePixelLimit(width, height);
        return new BufferedImage(width, height, source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    }

    private void requirePixelLimit(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > MAX_OUTPUT_PIXELS) {
            throw new IllegalArgumentException("Processed image exceeds the pixel limit");
        }
    }

    @PreDestroy
    public void close() throws OrtException {
        if (depthSession != null) depthSession.close();
    }
}
