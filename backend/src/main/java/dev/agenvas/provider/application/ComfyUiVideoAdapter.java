package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiInputImage;
import dev.agenvas.provider.infrastructure.ComfyUiHistory;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
import dev.agenvas.provider.infrastructure.ComfyUiVideoProperties;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fixed Wan I2V graph using the pinned input image version, origin and four model file basenames. */
@Component
public class ComfyUiVideoAdapter implements MediaAdapter {
    private final JooqMediaCapabilityRepository catalog;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ProjectService projects;
    private final ObjectMapper mapper;

    public ComfyUiVideoAdapter(JooqMediaCapabilityRepository catalog, ArtifactService artifacts,
            AssetService assets, ProjectService projects, ObjectMapper mapper) {
        this.catalog = catalog;
        this.artifacts = artifacts;
        this.assets = assets;
        this.projects = projects;
        this.mapper = mapper;
    }

    @Override public String adapterId() { return "COMFY_VIDEO_V1"; }

    @Override public boolean supports(PortInput input) {
        return input.kind() == Task.Kind.VIDEO_GENERATION
                && input.durationSeconds() >= 1 && input.durationSeconds() <= 5;
    }

    @Override public String candidateOriginSha256(AttemptContext context) {
        return snapshot(context).connectionVersion().originSha256();
    }

    @Override public Submission submit(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        ComfyUiClient client = client(snapshot);
        ComfyUiVideoWorkflow workflow = workflow(snapshot);
        Task task = context.lease();
        int durationSeconds = task.input().path("durationSeconds").asInt(-1);
        if (!workflow.supportsDurationSeconds(durationSeconds)) {
            throw new IllegalStateException("Approved duration exceeds fixed I2V template");
        }
        Project.AspectRatio ratio = ratio(context);
        UUID requestKey = UUID.fromString(context.requestKey());
        String uploaded = client.uploadImage(requestKey,
                pinnedInputImage(context.ownerId(), task, workflow, ratio), "png");
        long seed = requestKey.getMostSignificantBits() & Long.MAX_VALUE;
        UUID promptId = client.submit(workflow.renderSeconds(
                task.input().path("prompt").asText(),
                task.input().path("negativePrompt").asText(""), seed, uploaded,
                ratio, durationSeconds), requestKey);
        return new Submission.Accepted(promptId.toString());
    }

    @Override public Submission reconcile(AttemptContext context) {
        Snapshot snapshot = snapshot(context);
        ComfyUiClient client = client(snapshot);
        UUID promptId = UUID.fromString(context.originalRequestId());
        try {
            return switch (client.videoStatus(promptId, ComfyUiVideoWorkflow.OUTPUT_NODE_ID)) {
                case ComfyUiHistory.VideoPending ignored ->
                        new Submission.Pending(Instant.now().plusSeconds(5));
                case ComfyUiHistory.VideoFailed ignored ->
                        new Submission.Rejected("PROVIDER_EXECUTION_FAILED");
                case ComfyUiHistory.VideoReady ready -> new Submission.Completed(
                        new MediaPayload(client.output(ready.filename()), "video/mp4"));
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

    private Project.AspectRatio ratio(AttemptContext context) {
        String requested = VideoGenerationParameters.parse(context.lease().input()
                .path("mediaInput").path("parameters")).aspectRatio();
        if (VideoGenerationParameters.AUTO_ASPECT_RATIO.equals(requested)) {
            return projects.get(context.ownerId(), context.lease().projectId()).aspectRatio();
        }
        return switch (requested) {
            case VideoGenerationParameters.LANDSCAPE_ASPECT_RATIO -> Project.AspectRatio.LANDSCAPE_16_9;
            case VideoGenerationParameters.PORTRAIT_ASPECT_RATIO -> Project.AspectRatio.PORTRAIT_9_16;
            case VideoGenerationParameters.SQUARE_ASPECT_RATIO -> Project.AspectRatio.SQUARE_1_1;
            default -> throw new IllegalArgumentException("Unsupported video aspect ratio");
        };
    }

    private ComfyUiVideoWorkflow workflow(Snapshot snapshot) {
        JsonNode settings = mapper.readTree(snapshot.specJson()).path("settings");
        return new ComfyUiVideoWorkflow(new ComfyUiVideoProperties(true,
                settings.path("diffusionModel").asText(),
                settings.path("textEncoder").asText(),
                settings.path("vae").asText(),
                settings.path("clipVision").asText()), mapper);
    }

    /** Decode and normalize the exact pinned input image version, never a caller-supplied URL. */
    private byte[] pinnedInputImage(UUID ownerId, Task task, ComfyUiVideoWorkflow workflow,
            Project.AspectRatio ratio) {
        FrozenMediaInputs.Image image = FrozenMediaInputs.first(task);
        UUID imageId = image.artifactId();
        UUID versionId = image.versionId();
        ArtifactVersion version = artifacts.requireVersion(ownerId, task.projectId(),
                imageId, versionId);
        UUID assetId = UUID.fromString(version.content().path("assetId").asText());
        AssetService.AssetFile file = assets.get(ownerId, task.projectId(), assetId);
        if (file.asset().mediaKind() != Asset.MediaKind.IMAGE) {
            throw new IllegalStateException("Pinned video input is not an IMAGE Asset");
        }
        BufferedImage source;
        try {
            source = ImageIO.read(file.path().toFile());
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot decode pinned video input", failure);
        }
        if (source == null) throw new IllegalStateException("Pinned video input is undecodable");
        ComfyUiVideoWorkflow.Dimensions dimensions = workflow.dimensions(ratio);
        int width = dimensions.width();
        int height = dimensions.height();
        try {
            return ComfyUiInputImage.png(source, width, height);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode pinned video input", failure);
        }
    }
}
