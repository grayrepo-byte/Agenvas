package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.ComfyUiWorkflowDefinition;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiInputImage;
import dev.agenvas.provider.infrastructure.ComfyUiHistory;
import dev.agenvas.provider.infrastructure.ComfyUiImageProperties;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Execute the pinned published image graph; legacy fixed graphs remain available for recovery. */
@Component
public class ComfyUiImageAdapter implements MediaAdapter {
    private final MediaCapabilityService catalog;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ProjectService projects;
    private final ObjectMapper mapper;
    private final ComfyUiClientFactory clients;
    private final ComfyUiPublishedWorkflow published;

    // The catalog builds its registry from adapters; defer catalog resolution until execution.
    public ComfyUiImageAdapter(@Lazy MediaCapabilityService catalog, ArtifactService artifacts,
            AssetService assets, ProjectService projects, ObjectMapper mapper,
            ComfyUiClientFactory clients, ComfyUiPublishedWorkflow published) {
        this.catalog = catalog;
        this.artifacts = artifacts;
        this.assets = assets;
        this.projects = projects;
        this.mapper = mapper;
        this.clients = clients;
        this.published = published;
    }

    @Override public String adapterId() { return "COMFY_IMAGE_V1"; }

    @Override public boolean supports(PortInput input) {
        return input.kind() == Task.Kind.IMAGE_GENERATION;
    }

    @Override public Submission submit(AttemptContext context) {
        Snapshot snapshot = catalog.pinnedSnapshot(context.binding());
        ComfyUiClient client = clients.create(snapshot.connectionVersion());
        var settings = mapper.readTree(snapshot.specJson()).path("settings");
        if (ComfyUiWorkflowDefinition.configured(settings)) return published.submit(context, client, settings);
        ComfyUiImageWorkflow workflow = workflow(settings);
        Task task = context.lease();
        UUID requestKey = UUID.fromString(context.requestKey());
        boolean reference = !FrozenMediaInputs.images(task).isEmpty();
        String uploaded = client.uploadImage(requestKey,
                inputImage(context.ownerId(), task, reference), "png");
        long seed = requestKey.getMostSignificantBits() & Long.MAX_VALUE;
        String promptId = client.submit(workflow.render(task.input().path("prompt").asText(),
                task.input().path("negativePrompt").asText(""), seed, uploaded,
                reference), requestKey);
        return new Submission.Accepted(promptId);
    }

    @Override public Submission reconcile(AttemptContext context) {
        Snapshot snapshot = catalog.pinnedSnapshot(context.binding());
        ComfyUiClient client = clients.create(snapshot.connectionVersion());
        var settings = mapper.readTree(snapshot.specJson()).path("settings");
        if (ComfyUiWorkflowDefinition.configured(settings)) return published.reconcile(context, client, settings);
        String promptId = context.originalRequestId();
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

    private ComfyUiImageWorkflow workflow(JsonNode settings) {
        String checkpoint = settings.path("checkpoint").asText();
        return new ComfyUiImageWorkflow(new ComfyUiImageProperties(checkpoint), mapper);
    }

    /** Normalize the exact pinned reference image; the fixed graph always receives one PNG. */
    private byte[] inputImage(UUID ownerId, Task task, boolean reference) {
        ImageGenerationParameters parameters = ImageGenerationParameters.parse(
                task.input().path("mediaInput").path("parameters"));
        Project.AspectRatio ratio = switch (parameters.aspectRatio()) {
            case ImageGenerationParameters.AUTO_ASPECT_RATIO -> projects.get(ownerId, task.projectId()).aspectRatio();
            case "9:16" -> Project.AspectRatio.PORTRAIT_9_16;
            case "1:1" -> Project.AspectRatio.SQUARE_1_1;
            default -> Project.AspectRatio.LANDSCAPE_16_9;
        };
        var dimensions = ComfyUiInputImage.imageDimensions(ratio);
        int width = dimensions.width();
        int height = dimensions.height();
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
        try {
            return ComfyUiInputImage.png(source, width, height);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode ComfyUI input image", failure);
        }
    }
}
