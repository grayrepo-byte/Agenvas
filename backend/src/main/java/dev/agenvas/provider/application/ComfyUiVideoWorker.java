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
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 显式启用的固定 Wan 图生视频适配器；仅轮询已持久化的 Provider 请求 ID。 */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
@ConditionalOnProperty(prefix = "agenvas.provider.comfyui.video", name = "enabled",
        havingValue = "true")
public class ComfyUiVideoWorker {

    /** 提供任务认领、租约与提交检查点状态转换。 */
    private final TaskWorker worker;
    /** 读取 Worker 权限范围、任务固定输入和已受理 Provider 身份。 */
    private final TaskService tasks;
    /** 读取审批时固定的图片产物版本。 */
    private final ArtifactService artifacts;
    /** 读取关键帧文件并幂等归档视频输出。 */
    private final AssetService assets;
    /** 获取项目画幅设置，用于归一化关键帧。 */
    private final ProjectService projects;
    /** 向当前 ComfyUI 端点上传图片并提交固定工作流。 */
    private final ComfyUiClient client;
    /** 使用持久化的 Provider 身份只读轮询已受理请求。 */
    private final ComfyUiVideoPoller poller;
    /** 提供版本固定的图生视频模板及输入时长约束。 */
    private final ComfyUiVideoWorkflow workflow;
    /** 用于提交前核对当前 Provider 配置版本。 */
    private final PlanProviderProperties provider;

    /** 装配提交与轮询分离的视频 Worker，避免轮询路径重新提交生成请求。 */
    public ComfyUiVideoWorker(TaskService tasks, ArtifactService artifacts,
            AssetService assets, ProjectService projects, ComfyUiClient client,
            ComfyUiVideoPoller poller, ComfyUiVideoWorkflow workflow,
            PlanProviderProperties provider, CallLogService callLogs) {
        this.worker = new TaskWorker(tasks, callLogs);
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.assets = assets;
        this.projects = projects;
        this.client = client;
        this.poller = poller;
        this.workflow = workflow;
        this.provider = provider;
    }

    /** 在上传或非幂等 POST 前先提交任务检查点，再执行固定模板请求。 */
    public int submitOnce(String workerId) {
        return worker.runComfyVideosOnce(workerId, new TaskWorker.MediaHandler() {
            /** 返回当前 Provider 来源摘要供任务认领前校验。 */
            @Override
            public String candidateOriginSha256() {
                return client.originSha256();
            }

            /** 若配置、来源或工作流版本已变化，阻止向新端点提交旧计划。 */
            @Override
            public String preflightFailure(Task task) {
                return task.input().path("providerConfigVersion").asInt(-1)
                        == provider.configVersion()
                        && (task.planId() == null || client.originSha256().equals(
                                task.input().path("providerOriginSha256").asText()))
                        && workflow.version().equals(task.input().path("workflowVersion").asText())
                        ? null : "PROVIDER_CONFIG_CHANGED";
            }

            /** 只执行一次上传与提交；后续状态交给独立轮询流程。 */
            @Override
            public TaskWorker.Outcome execute(Task task, UUID requestKey) {
                return submit(task, requestKey);
            }
        });
    }

    /** 按原始请求 ID 查询已受理任务，不经过上传或提交代码路径。 */
    public int pollOnce(String workerId) {
        return poller.pollOnce(workerId);
    }

    /** 上传审批固定的关键帧，并用任务请求键生成种子后提交版本固定的模板。 */
    private TaskWorker.WaitingProvider submit(Task task, UUID requestKey) {
        UUID ownerId = tasks.ownerForWorker(task);
        Project.AspectRatio ratio = projects.get(ownerId, task.projectId()).aspectRatio();
        Duration duration = VideoDuration.fromFrozenTask(task.input());
        boolean wholeSeconds = task.input().path("schemaVersion").asInt(1) == 2;
        if (wholeSeconds && !workflow.supportsDurationSeconds(Math.toIntExact(duration.toSeconds()))
                || !wholeSeconds && !workflow.supportsDuration(Math.toIntExact(duration.toMillis()))) {
            throw new IllegalStateException("Approved shot duration exceeds fixed I2V capability");
        }
        byte[] input = pinnedKeyframe(ownerId, task, ratio);
        String uploaded = client.uploadImage(requestKey, input, "png");
        long seed = requestKey.getMostSignificantBits() & Long.MAX_VALUE;
        var graph = wholeSeconds
                ? workflow.renderSeconds(task.input().path("prompt").asText(),
                        task.input().path("negativePrompt").asText(""), seed, uploaded, ratio,
                        Math.toIntExact(duration.toSeconds()))
                : workflow.render(task.input().path("prompt").asText(),
                        task.input().path("negativePrompt").asText(""), seed, uploaded, ratio,
                        Math.toIntExact(duration.toMillis()));
        UUID promptId = client.submit(graph, requestKey);
        return new TaskWorker.WaitingProvider(promptId.toString(), Instant.now().plusSeconds(5));
    }

    /** 读取审批固定的历史图片版本，按项目画幅等比缩放并在空白底色上居中。 */
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
