package dev.agenvas.provider.application;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Component;

/** Owns the local Depth Anything session shared by image and video frame inference. */
@Component
public final class LocalDepthInference implements AutoCloseable {
    private static final int DEFAULT_DEPTH_EDGE = 518;
    private static final float[] IMAGENET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] IMAGENET_STD = {0.229f, 0.224f, 0.225f};

    private final LocalImageProperties properties;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private OrtSession session;
    private volatile boolean closed;

    public LocalDepthInference(LocalImageProperties properties) {
        this.properties = properties;
    }

    boolean available() {
        Path model = properties.depthModelPath();
        return !closed && model != null && Files.isRegularFile(model) && Files.isReadable(model);
    }

    BufferedImage depth(BufferedImage source) {
        // Concurrent inferences may share a session. Shutdown waits until their native results are released.
        lifecycle.readLock().lock();
        try {
            if (closed)
                throw new IllegalStateException("Depth inference is closed");
            return infer(source, depthSession());
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private synchronized OrtSession depthSession() {
        if (session != null)
            return session;
        if (!available())
            throw new IllegalStateException("Depth model is not configured");
        try (var options = new OrtSession.SessionOptions()) {
            var candidate = OrtEnvironment.getEnvironment().createSession(properties.depthModelPath().toString(),
                    options);
            try {
                if (candidate.getInputNames().size() != 1 || candidate.getOutputNames().isEmpty())
                    throw new IllegalStateException("Depth model must expose one input and an output");
                session = candidate;
            } finally {
                if (session == null)
                    candidate.close();
            }
            return session;
        } catch (OrtException failure) {
            throw new IllegalStateException("Cannot load depth model", failure);
        }
    }

    private BufferedImage infer(BufferedImage source, OrtSession session) {
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
        BufferedImage resized = LocalRaster.upscaleTo(source, width, height);
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
            float[][] depth = flattenDepth(result.get(0).getValue());
            BufferedImage map = normalizeDepth(depth, width, height);
            return LocalRaster.upscaleTo(map, source.getWidth(), source.getHeight());
        } catch (OrtException failure) {
            throw new IllegalStateException("Depth model inference failed", failure);
        }
    }

    private float[][] flattenDepth(Object value) {
        if (value instanceof float[][][] batch && batch.length == 1)
            return batch[0];
        if (value instanceof float[][][][] batch && batch.length == 1
                && batch[0].length == 1)
            return batch[0][0];
        if (value instanceof float[][] plane)
            return plane;
        throw new IllegalStateException("Unsupported depth model output");
    }

    private BufferedImage normalizeDepth(float[][] values, int width, int height) {
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        for (float[] row : values)
            for (float value : row) {
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
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int gray = Math.clamp(Math.round((values[y][x] - minimum) / range * 255f), 0, 255);
                image.getRaster().setSample(x, y, 0, gray);
            }
        return image;
    }

    @Override
    @PreDestroy
    public void close() throws OrtException {
        lifecycle.writeLock().lock();
        try {
            closed = true;
            if (session != null) {
                session.close();
                session = null;
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }
}
