package dev.agenvas.provider.application;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiHistory;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Recovers accepted fixed-v1 videos independently of whether new video submission is enabled. */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
public class ComfyUiVideoPoller {

    private final TaskWorker worker;
    private final TaskService tasks;
    private final AssetService assets;
    private final ComfyUiClientRegistry clientRegistry;
    private final ObjectMapper mapper;

    public ComfyUiVideoPoller(TaskService tasks, AssetService assets,
            ComfyUiClientRegistry clientRegistry, ObjectMapper mapper) {
        this.worker = new TaskWorker(tasks);
        this.tasks = tasks;
        this.assets = assets;
        this.clientRegistry = clientRegistry;
        this.mapper = mapper;
    }

    /** Only queries and downloads from the saved origin and prompt ID; never submits. */
    public int pollOnce(String workerId) {
        return worker.runComfyVideoPollsOnce(workerId, task -> {
            String savedOrigin = tasks.acceptedProviderOrigin(task).orElse(null);
            ComfyUiClient original = savedOrigin == null ? null : clientRegistry.forOriginal(
                    task.input().path("providerConfigVersion").asInt(-1), savedOrigin)
                    .orElse(null);
            if (original == null
                    || !ComfyUiVideoWorkflow.supportsHistoricalVersion(
                            task.input().path("workflowVersion").asText())
                    || task.planId() != null && !savedOrigin.equals(
                            task.input().path("providerOriginSha256").asText())) {
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

    /** Repeated downloads are reconciled through the task-keyed MP4 archive. */
    private TaskWorker.PollGenerated archive(Task task, UUID promptId, String filename,
            ComfyUiClient original) {
        UUID ownerId = tasks.ownerForWorker(task);
        Asset archived = assets.archiveTaskVideo(ownerId, task.projectId(), task.id(),
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
        content.put("keyframeVersionId", task.input().path("imageVersionId").asText());
        ObjectNode parameters = content.putObject("parameters");
        parameters.put("providerRequestId", promptId.toString());
        return new TaskWorker.PollGenerated(content);
    }
}
