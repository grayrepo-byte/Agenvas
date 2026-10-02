package dev.agenvas.provider.application;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiHistory;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 独立恢复已受理的固定版本视频任务；即使关闭新提交，历史请求仍可查询和归档。 */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiVideoPoller {

    /** 提供短事务认领、状态轮询租约和 fencing 终态提交。 */
    private final TaskWorker worker;
    /** 获取已受理来源摘要、任务所有者和持久输入。 */
    private final TaskService tasks;
    /** 按任务 ID 幂等归档 Provider 返回的视频文件。 */
    private final AssetService assets;
    /** 只在端点版本和摘要匹配时恢复历史客户端。 */
    private final ComfyUiClientRegistry clientRegistry;
    /** 构造归档后的视频产物内容。 */
    private final ObjectMapper mapper;

    /** 初始化独立轮询 Worker，不依赖是否装配视频提交组件。 */
    public ComfyUiVideoPoller(TaskService tasks, AssetService assets,
            ComfyUiClientRegistry clientRegistry, ObjectMapper mapper, CallLogService callLogs) {
        this.worker = new TaskWorker(tasks, callLogs);
        this.tasks = tasks;
        this.assets = assets;
        this.clientRegistry = clientRegistry;
        this.mapper = mapper;
    }

    /** 只向已保存来源查询原 prompt ID 并下载结果，绝不上传或重新提交生成请求。 */
    public int pollOnce(String workerId) {
        return worker.runComfyVideoPollsOnce(workerId, task -> {
            String savedOrigin = tasks.acceptedProviderOrigin(task).orElse(null);
            ComfyUiClient original = savedOrigin == null ? null : clientRegistry.forOriginal(
                    task.input().path("providerConfigVersion").asInt(-1), savedOrigin)
                    .orElse(null);
            if (original == null
                    || !ComfyUiVideoWorkflow.supportsHistoricalVersion(
                            task.input().path("workflowVersion").asText())
                    || (task.input().has("providerOriginSha256") && !savedOrigin.equals(
                            task.input().path("providerOriginSha256").asText()))) {
                return new TaskWorker.PollBlocked("PROVIDER_CONFIG_CHANGED");
            }
            UUID promptId = UUID.fromString(task.providerRequestId());
            try {
                ComfyUiHistory.VideoResult state = original.videoStatus(promptId,
                        ComfyUiVideoWorkflow.OUTPUT_NODE_ID);
                return switch (state) {
                    case ComfyUiHistory.VideoPending ignored ->
                        new TaskWorker.PollPending(Instant.now().plusSeconds(5));
                    case ComfyUiHistory.VideoFailed ignored ->
                        new TaskWorker.PollFailed("PROVIDER_EXECUTION_FAILED");
                    case ComfyUiHistory.VideoReady ready -> archive(task, promptId,
                            ready.filename(), original);
                };
            } catch (ComfyUiClient.ProtocolFailure failure) {
                return new TaskWorker.PollBlocked("PROVIDER_PROTOCOL_INVALID");
            }
        });
    }

    /** 重复查询或下载通过任务键 MP4 归档幂等合并。 */
    private TaskWorker.PollGenerated archive(Task task, UUID promptId, String filename,
            ComfyUiClient original) {
        UUID ownerId = tasks.ownerForWorker(task);
        Asset archived = assets.archiveTaskVideo(ownerId, task.projectId(), task.id(),
                () -> original.output(filename));
        ObjectNode content = MediaResult.content(mapper, task, archived.id(),
                task.input().path("prompt").asText());
        ObjectNode parameters = content.withObject("parameters");
        parameters.put("providerRequestId", promptId.toString());
        return new TaskWorker.PollGenerated(content);
    }
}
