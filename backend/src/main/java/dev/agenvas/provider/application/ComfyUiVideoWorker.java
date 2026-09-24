package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.plan.application.PlanProviderProperties;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
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

/** Opt-in fixed Wan I2V adapter; only a durable prompt ID is ever polled. */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
@ConditionalOnProperty(prefix = "agenvas.provider.comfyui.video", name = "enabled",
        havingValue = "true")
public class ComfyUiVideoWorker {

    private final TaskWorker worker;
    private final TaskService tasks;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ProjectService projects;
    private final ComfyUiClient client;
    private final ComfyUiVideoPoller poller;
    private final ComfyUiVideoWorkflow workflow;
    private final PlanProviderProperties provider;

    public ComfyUiVideoWorker(TaskService tasks, ArtifactService artifacts,
            AssetService assets, ProjectService projects, ComfyUiClient client,
            ComfyUiVideoPoller poller, ComfyUiVideoWorkflow workflow,
            PlanProviderProperties provider) {
        this.worker = new TaskWorker(tasks);
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.assets = assets;
        this.projects = projects;
        this.client = client;
        this.poller = poller;
        this.workflow = workflow;
        this.provider = provider;
    }

    /** The submission checkpoint is committed before upload or the non-idempotent POST. */
    public int submitOnce(String workerId) {
        return worker.runComfyVideosOnce(workerId, new TaskWorker.MediaHandler() {
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

    /** An accepted request is queried by its original ID; no path here can submit again. */
    public int pollOnce(String workerId) {
        return poller.pollOnce(workerId);
    }

    private TaskWorker.WaitingProvider submit(Task task, UUID requestKey) {
        UUID ownerId = tasks.ownerForWorker(task);
        Project.AspectRatio ratio = projects.get(ownerId, task.projectId()).aspectRatio();
        int durationMs = task.input().path("durationMs").asInt(-1);
        if (!workflow.supportsDuration(durationMs)) {
            throw new IllegalStateException("Approved shot duration exceeds fixed I2V capability");
        }
        byte[] input = pinnedKeyframe(ownerId, task, ratio);
        String uploaded = client.uploadImage(requestKey, input, "png");
        long seed = requestKey.getMostSignificantBits() & Long.MAX_VALUE;
        UUID promptId = client.submit(workflow.render(task.input().path("prompt").asText(),
                task.input().path("negativePrompt").asText(""), seed, uploaded, ratio,
                durationMs), requestKey);
        return new TaskWorker.WaitingProvider(promptId.toString(), Instant.now().plusSeconds(5));
    }

    /** Reads only the approved historical IMAGE version and letterboxes before upload. */
    private byte[] pinnedKeyframe(UUID ownerId, Task task, Project.AspectRatio ratio) {
        UUID imageId = UUID.fromString(task.input().path("imageArtifactId").asText());
        UUID versionId = UUID.fromString(task.input().path("imageVersionId").asText());
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
            throw new IllegalStateException("Cannot read pinned video input", failure);
        }
        if (source == null) throw new IllegalStateException("Pinned video input is undecodable");
        ComfyUiVideoWorkflow.Dimensions dimensions = workflow.dimensions(ratio);
        int width = dimensions.width();
        int height = dimensions.height();
        BufferedImage normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setColor(new Color(127, 127, 127));
            graphics.fillRect(0, 0, width, height);
            double scale = Math.min((double) width / source.getWidth(),
                    (double) height / source.getHeight());
            int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, (width - drawWidth) / 2, (height - drawHeight) / 2,
                    drawWidth, drawHeight, null);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(normalized, "png", output)) {
                throw new IllegalStateException("PNG encoder unavailable");
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode pinned video input", failure);
        }
    }

}
