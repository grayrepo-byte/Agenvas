package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.plan.application.PlanProviderProperties;
import dev.agenvas.provider.domain.GenerationGateway;
import dev.agenvas.provider.domain.GenerationRequest;
import dev.agenvas.provider.domain.GenerationResult;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Produces an unmistakable demo MP4 from the approved exact archived keyframe version. */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "mock", matchIfMissing = true)
public class MockVideoWorker {

    private final TaskWorker worker;
    private final TaskService tasks;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final MediaToolRunner mediaTools;
    private final GenerationGateway gateway;
    private final MockProviderProperties fixture;
    private final PlanProviderProperties provider;
    private final ObjectMapper mapper;

    public MockVideoWorker(TaskService tasks, ArtifactService artifacts, AssetService assets,
            MediaToolRunner mediaTools, GenerationGateway gateway,
            MockProviderProperties fixture, PlanProviderProperties provider, ObjectMapper mapper) {
        this.worker = new TaskWorker(tasks);
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.assets = assets;
        this.mediaTools = mediaTools;
        this.gateway = gateway;
        this.fixture = fixture;
        this.provider = provider;
        this.mapper = mapper;
    }

    /** Claims only video Tasks and never consumes an unapproved image or model turn. */
    public int runOnce(String workerId) {
        return worker.runVideosOnce(workerId, 1, new TaskWorker.MediaHandler() {
            @Override
            public String preflightFailure(Task task) {
                return task.input().path("providerConfigVersion").asInt(-1)
                        == provider.configVersion() ? null : "PROVIDER_CONFIG_CHANGED";
            }

            @Override
            public TaskWorker.Outcome execute(Task task, UUID requestKey) {
                return submit(task, requestKey);
            }
        });
    }

    /** A durable request key precedes every synchronous demo render and fenced success. */
    private TaskWorker.Outcome submit(Task task, UUID requestKey) {
        GenerationResult result = gateway.submit(new GenerationRequest(task.projectId(),
                requestKey.toString(), fixture.fixture()));
        return switch (result.status()) {
            case COMPLETED -> completed(task, result);
            case FAILED -> new TaskWorker.Failed(result.errorCode());
            case ACCEPTED -> throw new IllegalStateException(
                    "Mock video adapter unexpectedly returned asynchronous acceptance");
            case UNKNOWN -> throw new IllegalStateException(
                    "Mock video submission outcome is intentionally ambiguous");
        };
    }

    /** Reads only the exact validated image version, then archives the actual generated MP4. */
    private TaskWorker.GeneratedArtifact completed(Task task, GenerationResult result) {
        if (!result.demoOutput()) {
            throw new IllegalStateException("Mock video result lacks the demo marker");
        }
        UUID ownerId = tasks.ownerForWorker(task);
        UUID imageId = UUID.fromString(task.input().path("imageArtifactId").asText());
        UUID imageVersionId = UUID.fromString(task.input().path("imageVersionId").asText());
        UUID shotId = UUID.fromString(task.input().path("shotArtifactId").asText());
        UUID shotVersionId = UUID.fromString(task.input().path("shotVersionId").asText());
        ArtifactVersion image = artifacts.requireVersion(ownerId, task.projectId(),
                imageId, imageVersionId);
        ArtifactVersion shot = artifacts.requireVersion(ownerId, task.projectId(),
                shotId, shotVersionId);
        UUID imageAssetId = UUID.fromString(image.content().path("assetId").asText());
        AssetService.AssetFile input = assets.get(ownerId, task.projectId(), imageAssetId);
        if (input.asset().mediaKind() != Asset.MediaKind.IMAGE) {
            throw new IllegalStateException("Pinned video input is not an archived image");
        }
        int durationMs = shot.content().path("durationMs").asInt();
        if (durationMs < 100 || durationMs > 30_000) {
            throw new IllegalStateException("Pinned shot duration is invalid");
        }
        Path rendered;
        try {
            rendered = Files.createTempFile("agenvas-demo-video-", ".mp4");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create demo video output", exception);
        }
        try {
            String seconds = String.format(Locale.ROOT, "%.3f", durationMs / 1_000.0);
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-loop", "1", "-framerate", "24", "-i", input.path().toString(),
                    "-t", seconds,
                    "-vf", "scale=640:360:force_original_aspect_ratio=decrease,"
                            + "pad=640:360:(ow-iw)/2:(oh-ih)/2,format=yuv420p",
                    "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "25",
                    "-movflags", "+faststart",
                    "-y", rendered.toString()));
            Asset archived = assets.archiveTaskVideo(ownerId, task.projectId(), task.id(),
                    () -> {
                        try {
                            return Files.newInputStream(rendered);
                        } catch (IOException failure) {
                            throw new IllegalStateException("Cannot read generated demo MP4", failure);
                        }
                    });
            ObjectNode content = mapper.createObjectNode();
            content.put("assetId", archived.id().toString());
            content.put("prompt", task.input().path("prompt").asText("Mock video"));
            if (task.input().has("negativePrompt")) {
                content.put("negativePrompt", task.input().path("negativePrompt").asText());
            }
            content.put("providerConfigVersion", provider.configVersion());
            content.put("workflowVersion", task.input().path("workflowVersion").asText());
            content.put("sourceTaskId", task.id().toString());
            content.put("keyframeVersionId", imageVersionId.toString());
            ObjectNode parameters = content.putObject("parameters");
            parameters.put("mock", true);
            parameters.put("displayLabel", "演示视频（非 AI 生成）");
            parameters.put("providerRequestId", result.providerRequestId());
            return new TaskWorker.GeneratedArtifact(content);
        } finally {
            try {
                Files.deleteIfExists(rendered);
            } catch (IOException ignored) {
                // Temporary output is never the durable archived Asset.
            }
        }
    }
}
