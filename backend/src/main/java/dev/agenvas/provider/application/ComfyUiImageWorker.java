package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.plan.application.PlanProviderProperties;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiHistory;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Approved image-v1 Tasks submit one fixed graph, then query only the saved prompt id. */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiImageWorker {

    private final TaskWorker worker;
    private final TaskService tasks;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ProjectService projects;
    private final ComfyUiClient client;
    private final ComfyUiClientRegistry clientRegistry;
    private final ComfyUiImageWorkflow workflow;
    private final PlanProviderProperties provider;
    private final ObjectMapper mapper;

    public ComfyUiImageWorker(TaskService tasks, ArtifactService artifacts,
            AssetService assets, ProjectService projects, ComfyUiClient client,
            ComfyUiClientRegistry clientRegistry, ComfyUiImageWorkflow workflow,
            PlanProviderProperties provider, ObjectMapper mapper) {
        this.worker = new TaskWorker(tasks);
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.assets = assets;
        this.projects = projects;
        this.client = client;
        this.clientRegistry = clientRegistry;
        this.workflow = workflow;
        this.provider = provider;
        this.mapper = mapper;
    }

    /** A bounded scheduled pass enters SUBMITTING before either upload or prompt submission. */
    public int submitOnce(String workerId) {
        return worker.runComfyImagesOnce(workerId, new TaskWorker.MediaHandler() {
            @Override
            public String candidateOriginSha256() {
                return client.originSha256();
            }

            @Override
            public String preflightFailure(Task task) {
                return task.input().path("providerConfigVersion").asInt(-1)
                        == provider.configVersion()
                        && (task.planId() == null || client.originSha256().equals(
                                task.input().path("providerOriginSha256").asText()))
                        && workflow.version().equals(task.input().path("workflowVersion").asText())
                        ? null : "PROVIDER_CONFIG_CHANGED";
            }

            @Override
            public TaskWorker.Outcome execute(Task task, UUID requestKey) {
                return submit(task, requestKey);
            }
        });
    }

    /** Querying a saved request is a different worker path; it never calls submit or upload. */
    public int pollOnce(String workerId) {
        return worker.runComfyImagePollsOnce(workerId, task -> {
            String savedOrigin = tasks.acceptedProviderOrigin(task).orElse(null);
            ComfyUiClient original = savedOrigin == null ? null : clientRegistry.forOriginal(
                    task.input().path("providerConfigVersion").asInt(-1), savedOrigin)
                    .orElse(null);
            if (original == null
                    || !ComfyUiImageWorkflow.supportsHistoricalVersion(
                            task.input().path("workflowVersion").asText())
                    || task.planId() != null && !savedOrigin.equals(
                            task.input().path("providerOriginSha256").asText())) {
                return new TaskWorker.PollBlocked("PROVIDER_CONFIG_CHANGED");
            }
            UUID promptId = UUID.fromString(task.providerRequestId());
            try {
                ComfyUiHistory.ImageResult state = original.imageStatus(promptId,
                        ComfyUiImageWorkflow.OUTPUT_NODE_ID);
                return switch (state) {
                    case ComfyUiHistory.Pending ignored ->
                        new TaskWorker.PollPending(Instant.now().plusSeconds(5));
                    case ComfyUiHistory.Failed ignored ->
                        new TaskWorker.PollFailed("PROVIDER_EXECUTION_FAILED");
                    case ComfyUiHistory.Ready ready -> archive(task, promptId, ready.filename(),
                            original);
                };
            } catch (ComfyUiClient.ProtocolFailure failure) {
                return new TaskWorker.PollBlocked("PROVIDER_PROTOCOL_INVALID");
            }
        });
    }

    private TaskWorker.WaitingProvider submit(Task task, UUID requestKey) {
        UUID ownerId = tasks.ownerForWorker(task);
        boolean reference = task.input().has("referenceImageVersionId");
        byte[] image = inputImage(ownerId, task, reference);
        String uploaded = client.uploadImage(requestKey, image, "png");
        String prompt = task.input().path("prompt").asText();
        String negative = task.input().path("negativePrompt").asText("");
        long seed = requestKey.getMostSignificantBits() & Long.MAX_VALUE;
        UUID promptId = client.submit(workflow.render(prompt, negative, seed,
                uploaded, reference), requestKey);
        return new TaskWorker.WaitingProvider(promptId.toString(), Instant.now().plusSeconds(5));
    }

    /** Real selected image bytes are normalized and uploaded into LoadImage's latent path. */
    private byte[] inputImage(UUID ownerId, Task task, boolean reference) {
        Project.AspectRatio ratio = projects.get(ownerId, task.projectId()).aspectRatio();
        int width = ratio == Project.AspectRatio.PORTRAIT_9_16 ? 576
                : ratio == Project.AspectRatio.SQUARE_1_1 ? 768 : 1024;
        int height = ratio == Project.AspectRatio.PORTRAIT_9_16 ? 1024
                : ratio == Project.AspectRatio.SQUARE_1_1 ? 768 : 576;
        BufferedImage source = null;
        if (reference) {
            UUID versionId = UUID.fromString(task.input().path("referenceImageVersionId").asText());
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

    /** Download and archive are repeatable; a failed archive never creates another prompt. */
    private TaskWorker.PollGenerated archive(Task task, UUID promptId, String filename,
            ComfyUiClient original) {
        UUID ownerId = tasks.ownerForWorker(task);
        Asset archived = assets.archiveTaskImage(ownerId, task.projectId(), task.id(),
                () -> original.output(filename));
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", archived.id().toString());
        content.put("prompt", task.input().path("prompt").asText());
        if (task.input().has("negativePrompt")) {
            content.put("negativePrompt", task.input().path("negativePrompt").asText());
        }
        content.put("providerConfigVersion", task.input().path("providerConfigVersion").asInt());
        content.put("workflowVersion", task.input().path("workflowVersion").asText());
        content.put("sourceTaskId", task.id().toString());
        ObjectNode parameters = content.putObject("parameters");
        parameters.put("providerRequestId", promptId.toString());
        parameters.put("referenceImageVersionId",
                task.input().path("referenceImageVersionId").asText(""));
        return new TaskWorker.PollGenerated(content);
    }
}
