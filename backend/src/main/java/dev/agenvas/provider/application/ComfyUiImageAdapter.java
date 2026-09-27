package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiHistory;
import dev.agenvas.provider.infrastructure.ComfyUiImageProperties;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.task.domain.Task;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Fixed image graph using the exact connection and model filename version approved by the user. */
@Component
public class ComfyUiImageAdapter implements MediaAdapter {
    private final JooqMediaCapabilityRepository catalog;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ProjectService projects;
    private final ObjectMapper mapper;

    public ComfyUiImageAdapter(JooqMediaCapabilityRepository catalog, ArtifactService artifacts,
            AssetService assets, ProjectService projects, ObjectMapper mapper) {
        this.catalog = catalog;
        this.artifacts = artifacts;
        this.assets = assets;
        this.projects = projects;
        this.mapper = mapper;
    }

    @Override public String adapterId() { return "COMFY_IMAGE_V1"; }

    @Override public boolean supports(PortInput input) {
        return input.kind() == Task.Kind.IMAGE_GENERATION;
    }

    @Override public String candidateOriginSha256(AttemptContext context) {
        return snapshot(context).connectionVersion().originSha256();
    }

    @Override public Submission submit(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        ComfyUiClient client = client(snapshot);
        ComfyUiImageWorkflow workflow = workflow(snapshot);
        Task task = context.lease();
        UUID requestKey = UUID.fromString(context.requestKey());
        boolean reference = !FrozenMediaInputs.images(task).isEmpty();
        String uploaded = client.uploadImage(requestKey,
                inputImage(context.ownerId(), task, reference), "png");
        long seed = requestKey.getMostSignificantBits() & Long.MAX_VALUE;
        UUID promptId = client.submit(workflow.render(task.input().path("prompt").asText(),
                task.input().path("negativePrompt").asText(""), seed, uploaded,
                reference), requestKey);
        return new Submission.Accepted(promptId.toString());
    }

    @Override public Submission reconcile(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        ComfyUiClient client = client(snapshot);
        UUID promptId = UUID.fromString(context.originalRequestId());
        try {
            return switch (client.imageStatus(promptId, ComfyUiImageWorkflow.OUTPUT_NODE_ID)) {
                case ComfyUiHistory.Pending ignored ->
                        new Submission.Pending(Instant.now().plusSeconds(5));
                case ComfyUiHistory.Failed ignored ->
                        new Submission.Rejected("PROVIDER_EXECUTION_FAILED");
                case ComfyUiHistory.Ready ready -> new Submission.Completed(
                        new dev.agenvas.provider.domain.MediaPayload(
                                client.output(ready.filename()), "image/png"));
            };
        } catch (ComfyUiClient.ProtocolFailure invalid) {
            return new Submission.Blocked("PROVIDER_PROTOCOL_INVALID");
        }
    }

    private ComfyUiClient client(Snapshot snapshot) {
        String origin = snapshot.connectionVersion().origin();
        if (origin == null || snapshot.connectionVersion().originSha256() == null) {
            throw new IllegalStateException("Pinned ComfyUI origin is missing");
        }
        ComfyUiClient client = new ComfyUiClient(new ComfyUiProperties(origin), mapper);
        if (!snapshot.connectionVersion().originSha256().equals(client.originSha256())) {
            throw new IllegalStateException("Pinned ComfyUI origin fingerprint differs");
        }
        return client;
    }

    private Snapshot snapshot(AttemptContext context) {
        var binding = context.binding();
        Snapshot snapshot = catalog.snapshotAt(binding.capabilityId(),
                binding.capabilityVersion(), binding.connectionId(),
                binding.connectionVersion()).orElseThrow(() ->
                new IllegalStateException("Pinned media capability version is missing"));
        if (!binding.adapterId().equals(snapshot.adapterId())
                || !binding.mappingSha256().equals(snapshot.mappingSha256())) {
            throw new IllegalStateException("Pinned media adapter identity differs");
        }
        return snapshot;
    }

    private ComfyUiImageWorkflow workflow(Snapshot snapshot) {
        String checkpoint = mapper.readTree(snapshot.specJson()).path("settings")
                .path("checkpoint").asText();
        return new ComfyUiImageWorkflow(new ComfyUiImageProperties(checkpoint), mapper);
    }

    /** Normalize the exact pinned reference image; the fixed graph always receives one PNG. */
    private byte[] inputImage(UUID ownerId, Task task, boolean reference) {
        Project.AspectRatio ratio = projects.get(ownerId, task.projectId()).aspectRatio();
        int width = ratio == Project.AspectRatio.PORTRAIT_9_16 ? 576
                : ratio == Project.AspectRatio.SQUARE_1_1 ? 768 : 1024;
        int height = ratio == Project.AspectRatio.PORTRAIT_9_16 ? 1024
                : ratio == Project.AspectRatio.SQUARE_1_1 ? 768 : 576;
        BufferedImage source = null;
        if (reference) {
            UUID versionId = FrozenMediaInputs.first(task).versionId();
            ArtifactVersion version = artifacts.requireImageVersionForTask(ownerId,
                    task.projectId(), versionId);
            UUID assetId = UUID.fromString(version.content().path("assetId").asText());
            AssetService.AssetFile file = assets.get(ownerId, task.projectId(), assetId);
            if (file.asset().mediaKind() != Asset.MediaKind.IMAGE) {
                throw new IllegalStateException("Pinned reference is not an image Asset");
            }
            try {
                source = ImageIO.read(file.path().toFile());
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot decode pinned reference", failure);
            }
            if (source == null) throw new IllegalStateException("Pinned reference is not decodable");
        }
        BufferedImage normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setColor(new Color(127, 127, 127));
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
            if (!ImageIO.write(normalized, "png", output)) {
                throw new IllegalStateException("PNG encoder unavailable");
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode ComfyUI input image", failure);
        }
    }
}
