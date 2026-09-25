package dev.agenvas.provider.application;

import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
import dev.agenvas.provider.infrastructure.JdbcMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.UnknownTaskReconciler;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 只在原 ComfyUI 配置和实例上核对已预提交 prompt ID，不会再次创建请求。 */
@Service
public class ComfyUiUnknownTaskReconciler implements UnknownTaskReconciler {

    /** 读取提交尝试并以版本条件恢复已核实的原任务。 */
    private final TaskService tasks;
    /** 按原配置版本和端点摘要查找 Provider 客户端。 */
    private final ComfyUiClientRegistry clientRegistry;
    private final JdbcMediaCapabilityRepository catalog;
    private final ObjectMapper mapper;

    /** 注入任务恢复服务与历史 Provider 客户端注册表。
     * @param tasks 提交尝试读取和条件恢复服务
     * @param clientRegistry 原配置对应的客户端查找器
     */
    @Autowired
    public ComfyUiUnknownTaskReconciler(TaskService tasks,
            ObjectProvider<ComfyUiClientRegistry> clientRegistry,
            JdbcMediaCapabilityRepository catalog, ObjectMapper mapper) {
        this.tasks = tasks;
        this.clientRegistry = clientRegistry.getIfAvailable();
        this.catalog = catalog;
        this.mapper = mapper;
    }

    /** Legacy test/recovery entry for an explicitly supplied historical registry. */
    public ComfyUiUnknownTaskReconciler(TaskService tasks,
            ComfyUiClientRegistry clientRegistry) {
        this.tasks = tasks;
        this.clientRegistry = clientRegistry;
        this.catalog = null;
        this.mapper = null;
    }

    /** 业务状态事务之外执行 Provider 查询，避免网络调用占用数据库事务。 */
    @Override
    public Result reconcile(UUID ownerId, UUID projectId, UUID taskId) {
        TaskService.ReconciliationCandidate candidate = tasks.reconciliationCandidate(
                ownerId, projectId, taskId);
        Task task = candidate.task();
        String savedOrigin = candidate.attempt().candidateOriginSha256();
        var bound = tasks.mediaBinding(task);
        boolean supported;
        ComfyUiClient original;
        if (bound.isPresent() && catalog != null && mapper != null) {
            var binding = bound.get();
            var snapshot = catalog.snapshotAt(binding.capabilityId(),
                    binding.capabilityVersion(), binding.connectionId(),
                    binding.connectionVersion()).orElse(null);
            supported = snapshot != null && binding.adapterId().equals(snapshot.adapterId())
                    && binding.mappingSha256().equals(snapshot.mappingSha256())
                    && ("COMFY_IMAGE_V1".equals(binding.adapterId())
                    || "COMFY_VIDEO_V1".equals(binding.adapterId()));
            String origin = snapshot == null ? null : snapshot.connectionVersion().origin();
            original = supported && origin != null && savedOrigin != null
                    && savedOrigin.equals(snapshot.connectionVersion().originSha256())
                    ? new ComfyUiClient(new ComfyUiProperties(origin), mapper) : null;
            if (original != null && !savedOrigin.equals(original.originSha256())) {
                original = null;
            }
        } else {
            String workflowVersion = task.input().path("workflowVersion").asText();
            supported = task.kind() == Task.Kind.IMAGE_GENERATION
                    ? ComfyUiImageWorkflow.supportsHistoricalVersion(workflowVersion)
                    : task.kind() == Task.Kind.VIDEO_GENERATION
                            && ComfyUiVideoWorkflow.supportsHistoricalVersion(workflowVersion);
            original = clientRegistry == null ? null : clientRegistry.forOriginal(
                    task.input().path("providerConfigVersion").asInt(-1), savedOrigin)
                    .orElse(null);
        }
        if (original == null || !supported
                || task.planId() != null && !savedOrigin.equals(
                        task.input().path("providerOriginSha256").asText())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "PROVIDER_CONFIG_CHANGED",
                    "Provider 配置已变化", "旧请求必须在原配置与原实例核对，当前配置不能自动恢复。", false);
        }
        UUID originalId = candidate.attempt().candidateRequestId();
        boolean found;
        try {
            found = original.originalPromptExists(originalId);
        } catch (ComfyUiClient.ProtocolFailure | ComfyUiClient.TransportFailure failure) {
            throw new ApiProblemException(HttpStatus.BAD_GATEWAY, "PROVIDER_RECONCILIATION_FAILED",
                    "原请求核对失败", "Provider 查询不可用或身份不匹配，任务仍为 UNKNOWN。", true);
        }
        if (!found) return new Result(Outcome.NO_EVIDENCE, task);
        return new Result(Outcome.RESUMED, tasks.resumeVerifiedOriginal(ownerId, projectId,
                taskId, task.version(), candidate.attempt().id(), originalId,
                candidate.attempt().candidateOriginSha256()));
    }
}
