package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.domain.ImageOperation;
import dev.agenvas.task.domain.Task;
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
            return prepare(context).preflightFailure();
        } catch (RuntimeException invalid) {
            return "PROVIDER_UNSUPPORTED_INPUT";
        }
    }

    @Override
    public Submission submit(AttemptContext context) {
        try {
            BufferedImage output = prepare(context).execute();
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

    private LocalImageOperations.Prepared prepare(AttemptContext context) {
        JsonNode operation = operation(context.lease());
        BufferedImage image = source(context, operation);
        return LocalImageOperations.prepare(image, ImageOperation.valueOf(operation.path("name").asText()),
                operation.path("parameters"), depth);
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
}
