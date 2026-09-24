package dev.agenvas.provider.application;

import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.UnknownTaskReconciler;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Queries only the fixed ComfyUI origin for an exact precommitted prompt ID. */
@Service
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiUnknownTaskReconciler implements UnknownTaskReconciler {

    private final TaskService tasks;
    private final ComfyUiClientRegistry clientRegistry;
    public ComfyUiUnknownTaskReconciler(TaskService tasks,
            ComfyUiClientRegistry clientRegistry) {
        this.tasks = tasks;
        this.clientRegistry = clientRegistry;
    }

    /** No provider network call runs in the transaction that changes business state. */
    @Override
    public Result reconcile(UUID ownerId, UUID projectId, UUID taskId) {
        TaskService.ReconciliationCandidate candidate = tasks.reconciliationCandidate(
                ownerId, projectId, taskId);
        Task task = candidate.task();
        String workflowVersion = task.input().path("workflowVersion").asText();
        boolean supported = task.kind() == Task.Kind.IMAGE_GENERATION
                ? ComfyUiImageWorkflow.supportsHistoricalVersion(workflowVersion)
                : task.kind() == Task.Kind.VIDEO_GENERATION
                        && ComfyUiVideoWorkflow.supportsHistoricalVersion(workflowVersion);
        String savedOrigin = candidate.attempt().candidateOriginSha256();
        ComfyUiClient original = clientRegistry.forOriginal(
                task.input().path("providerConfigVersion").asInt(-1), savedOrigin).orElse(null);
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
