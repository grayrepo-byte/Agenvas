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
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 从审批固定的归档关键帧生成明确标为演示素材的 MP4，不调用真实视频模型。 */
@Component
public class MockVideoWorker {

    /** 负责有限批次认领、租约续期和带 fencing 的任务终态更新。 */
    private final TaskWorker worker;
    /** 查询任务所有者并校验已固定的生成输入。 */
    private final TaskService tasks;
    /** 读取审批指定的镜头和关键帧历史版本。 */
    private final ArtifactService artifacts;
    /** 读取关键帧资产并幂等归档输出视频。 */
    private final AssetService assets;
    /** 通过受控 FFmpeg 参数合成演示视频。 */
    private final MediaToolRunner mediaTools;
    /** 产生本地演示结果，不依赖外部媒体 Provider。 */
    private final GenerationGateway gateway;
    /** 选择应用内置或部署提供的演示图片素材。 */
    private final MockProviderProperties fixture;
    /** 将当前 Provider 配置版本写入结果并校验任务输入。 */
    private final PlanProviderProperties provider;
    /** 构造生成结果中的 JSON 内容。 */
    private final ObjectMapper mapper;

    /** 组装演示视频 Worker 的任务、素材和固定媒体工具依赖。 */
    public MockVideoWorker(TaskService tasks, ArtifactService artifacts, AssetService assets,
            MediaToolRunner mediaTools, GenerationGateway gateway,
            MockProviderProperties fixture, PlanProviderProperties provider, ObjectMapper mapper, CallLogService callLogs) {
        this.worker = new TaskWorker(tasks, callLogs);
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.assets = assets;
        this.mediaTools = mediaTools;
        this.gateway = gateway;
        this.fixture = fixture;
        this.provider = provider;
        this.mapper = mapper;
    }

    /** 仅认领已批准的视频任务，不处理图片任务或尚未审批的模型回合。 */
    public int runOnce(String workerId) {
        return worker.runVideosOnce(workerId, 1, new TaskWorker.MediaHandler() {
            /** Provider 配置版本变化时在提交演示生成前阻止旧任务继续。 */
            @Override
            public String preflightFailure(Task task) {
                return task.input().path("providerConfigVersion").asInt(-1)
                        == provider.configVersion() ? null : "PROVIDER_CONFIG_CHANGED";
            }

            /** 使用持久化请求键执行一次本地演示生成。 */
            @Override
            public TaskWorker.Outcome execute(Task task, UUID requestKey) {
                return executeBound(task, requestKey);
            }
        });
    }

    /** 在同步演示渲染前已有持久请求键，成功结果再由 Worker fencing 提交。 */
    TaskWorker.Outcome executeBound(Task task, UUID requestKey) {
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

    /** 仅读取审批指定的图片版本，再归档 FFmpeg 实际生成的 MP4 文件。 */
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
        Duration duration = VideoDuration.fromFrozenTask(task.input());
        if (task.input().path("schemaVersion").asInt(1) == 2
                && shot.content().path("durationSeconds").asInt(-1) != duration.toSeconds()) {
            throw new IllegalStateException("Pinned shot duration differs from approved Task");
        }
        Path rendered;
        try {
            rendered = Files.createTempFile("agenvas-demo-video-", ".mp4");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create demo video output", exception);
        }
        try {
            String seconds = task.input().path("schemaVersion").asInt(1) == 2
                    ? Long.toString(duration.toSeconds())
                    : String.format(Locale.ROOT, "%.3f", duration.toMillis() / 1_000.0);
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
            content.put("providerConfigVersion", task.input().path("providerConfigVersion").asInt());
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
