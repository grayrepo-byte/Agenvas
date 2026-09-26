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
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 执行已审批的固定图片工作流；提交与轮询分开，轮询只使用已保存的 prompt ID。 */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiImageWorker {

    /** 提供短事务认领、租约与 fencing epoch 的媒体任务状态编排。 */
    private final TaskWorker worker;
    /** 查询 Worker 身份、固定 Provider 来源并读取任务输入。 */
    private final TaskService tasks;
    /** 解析任务固定的参考图版本及产物归档内容。 */
    private final ArtifactService artifacts;
    /** 读取参考图字节并按任务幂等键归档生成结果。 */
    private final AssetService assets;
    /** 读取项目画幅设置，决定上传前的归一化尺寸。 */
    private final ProjectService projects;
    /** 当前候选 ComfyUI 端点客户端。 */
    private final ComfyUiClient client;
    /** 按已接受的端点身份恢复历史任务的客户端。 */
    private final ComfyUiClientRegistry clientRegistry;
    /** 版本固定的服务端图片图模板。 */
    private final ComfyUiImageWorkflow workflow;
    /** 当前 Provider 配置版本，用于提交前拒绝配置漂移。 */
    private final PlanProviderProperties provider;
    /** 构造生成结果所需的 Artifact 内容 JSON。 */
    private final ObjectMapper mapper;

    /** 组装固定图片模板的提交、历史轮询和素材归档依赖。 */
    public ComfyUiImageWorker(TaskService tasks, ArtifactService artifacts,
            AssetService assets, ProjectService projects, ComfyUiClient client,
            ComfyUiClientRegistry clientRegistry, ComfyUiImageWorkflow workflow,
            PlanProviderProperties provider, ObjectMapper mapper, CallLogService callLogs) {
        this.worker = new TaskWorker(tasks, callLogs);
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

    /** 每轮限量认领任务，并在上传或提交 prompt 前先持久化进入 SUBMITTING。 */
    public int submitOnce(String workerId) {
        return worker.runComfyImagesOnce(workerId, new TaskWorker.MediaHandler() {
            /** 返回当前候选端点摘要，任务认领前用于与审批来源比对。 */
            @Override
            public String candidateOriginSha256() {
                return client.originSha256();
            }

            /** 检查计划固定的 Provider、端点和工作流版本是否仍可提交。 */
            @Override
            public String preflightFailure(Task task) {
                return task.input().path("providerConfigVersion").asInt(-1)
                        == provider.configVersion()
                        && (task.planId() == null || client.originSha256().equals(
                                task.input().path("providerOriginSha256").asText()))
                        && workflow.version().equals(task.input().path("workflowVersion").asText())
                        ? null : "PROVIDER_CONFIG_CHANGED";
            }

            /** 转交实际上传与 prompt 提交；Worker 已先持久化 SUBMITTING 检查点。 */
            @Override
            public TaskWorker.Outcome execute(Task task, UUID requestKey) {
                return submit(task, requestKey);
            }
        });
    }

    /** 已受理请求走独立轮询路径；此路径不会再次上传或提交。 */
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

    /** 生成确定性种子的输入图并以请求键上传、提交固定模板。 */
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

    /** 将选定参考图字节归一化后上传，供固定模板的 LoadImage 节点读取。 */
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

    /** 下载和归档可重试；归档失败时不会重新创建 Provider prompt。 */
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
