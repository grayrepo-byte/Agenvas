package dev.agenvas.provider.application;

import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Routes only version-pinned media work through installed fixed Java adapters. */
@Component
public class MediaExecutionWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaExecutionWorker.class);

    private final TaskService tasks;
    private final CallLogService callLogs;
    private final MediaCapabilityService catalog;
    private final MediaAdapterRegistry adapters;
    private final AssetService assets;
    private final ObjectMapper mapper;

    public MediaExecutionWorker(TaskService tasks, MediaCapabilityService catalog,
            MediaAdapterRegistry adapters, AssetService assets, ObjectMapper mapper, CallLogService callLogs) {
        this.callLogs = callLogs;
        this.tasks = tasks;
        this.catalog = catalog;
        this.adapters = adapters;
        this.assets = assets;
        this.mapper = mapper;
    }

    /** The claim and checkpoint are short transactions; provider traffic happens afterward. */
    public int submitOnce(String workerId) {
        List<Task> claimed = tasks.claimBoundMedia(workerId, 1);
        for (Task task : claimed) {
            MediaCapabilityBinding binding = tasks.mediaBinding(task).orElseThrow();
            int seconds = task.kind() == Task.Kind.VIDEO_GENERATION
                    ? task.input().path("durationSeconds").asInt(-1) : 0;
            if (!catalog.isCurrentBinding(binding, task.kind(), seconds)) {
                tasks.blockPreSubmission(task, workerId, "MEDIA_CAPABILITY_CHANGED");
                continue;
            }
            MediaAdapter adapter;
            try {
                adapter = adapters.require(binding.adapterId());
            } catch (RuntimeException unavailable) {
                tasks.blockPreSubmission(task, workerId, "PROVIDER_UNSUPPORTED_CAPABILITY");
                continue;
            }
            UUID ownerId = tasks.ownerForWorker(task);
            AttemptContext preflight = new AttemptContext(task, binding, ownerId, null, null);
            String preflightFailure = adapter.preflightFailure(preflight);
            if (preflightFailure != null) {
                tasks.blockPreSubmission(task, workerId, preflightFailure);
                continue;
            }
            String origin = adapter.candidateOriginSha256(preflight);
            UUID requestKey;
            try {
                requestKey = tasks.beginSubmission(task, workerId, origin, binding);
            } catch (dev.agenvas.shared.error.ApiProblemException problem) {
                if ("TASK_INPUT_STALE".equals(problem.code())
                        || "TASK_PROJECT_ARCHIVED".equals(problem.code())
                        || "MEDIA_CAPABILITY_CHANGED".equals(problem.code())) {
                    tasks.blockPreSubmission(task, workerId, problem.code());
                    continue;
                }
                throw problem;
            }
            AttemptContext attempt = new AttemptContext(task, binding, ownerId,
                    requestKey.toString(), null);
            // A network exception leaves SUBMITTING for lease expiry and UNKNOWN recovery.
            Submission result = callLogs.record(descriptor(task, binding, CallLog.Operation.SUBMIT),
                    () -> adapter.submit(attempt), resultValue -> callOutcome(resultValue, null));
            recordSubmission(task, workerId, attempt, result);
        }
        return claimed.size();
    }

    /** Accepted requests can only be queried by their persisted original request ID. */
    public int pollOnce(String workerId) {
        List<Task> claimed = tasks.claimBoundMediaPolls(workerId, 1);
        for (Task task : claimed) {
            MediaCapabilityBinding binding = tasks.mediaBinding(task).orElseThrow();
            MediaAdapter adapter = adapters.require(binding.adapterId());
            AttemptContext attempt = new AttemptContext(task, binding,
                    tasks.ownerForWorker(task), null, task.providerRequestId());
            Submission result;
            try {
                result = callLogs.record(descriptor(task, binding, CallLog.Operation.POLL),
                        () -> adapter.reconcile(attempt), resultValue -> callOutcome(resultValue, task.providerRequestId()));
            } catch (RuntimeException transientFailure) {
                tasks.retryProviderPoll(task, workerId, "PROVIDER_POLL_TECHNICAL_FAILURE");
                continue;
            }
            switch (result) {
                case Submission.Pending pending ->
                    tasks.deferProviderPoll(task, workerId, pending.nextActionAt());
                case Submission.Completed completed -> {
                    ObjectNode content;
                    try {
                        content = archive(attempt, completed.payload());
                    } catch (RuntimeException archiveFailure) {
                        tasks.retryProviderPoll(task, workerId,
                                "PROVIDER_POLL_TECHNICAL_FAILURE");
                        continue;
                    }
                    tasks.succeedWithArtifact(task, workerId, content);
                }
                case Submission.CompletedArtifact completed ->
                    tasks.succeedWithArtifact(task, workerId, completed.content());
                case Submission.Rejected rejected -> tasks.fail(task, workerId, rejected.code());
                case Submission.Blocked blocked -> tasks.blockProviderPoll(task, workerId,
                        blocked.code());
                case Submission.Unknown ignored ->
                    tasks.retryProviderPoll(task, workerId, "PROVIDER_POLL_TECHNICAL_FAILURE");
                case Submission.Accepted ignored ->
                    tasks.deferProviderPoll(task, workerId, Instant.now().plusSeconds(5));
            }
        }
        return claimed.size();
    }

    private CallLogService.CallDescriptor descriptor(Task task, MediaCapabilityBinding binding,
            CallLog.Operation operation) {
        var snapshot = catalog.pinnedSnapshot(binding);
        var spec = mapper.readTree(snapshot.specJson());
        String model = spec.path("settings").path("model").asText("");
        if (model.isEmpty()) model = spec.path("modelId").asText(null);
        if (model == null) model = spec.path("settings").path("checkpoint").asText(null);
        if (model == null) model = spec.path("settings").path("diffusionModel").asText(null);
        boolean mock = snapshot.connection().platform() == MediaPlatform.MOCK;
        return new CallLogService.CallDescriptor(task.projectId(), task.id(), task.runId(), null,
                task.kind() == Task.Kind.AUDIO_GENERATION ? CallLog.Kind.AUDIO
                        : task.kind() == Task.Kind.IMAGE_GENERATION ? CallLog.Kind.IMAGE : CallLog.Kind.VIDEO,
                operation, binding.adapterId(), model, mock);
    }

    private CallLogService.CallOutcome callOutcome(Submission result, String originalRequestId) {
        return switch (result) {
            case Submission.Accepted accepted -> CallLogService.CallOutcome.succeeded(accepted.requestId());
            case Submission.Rejected rejected -> new CallLogService.CallOutcome(
                    CallLog.Status.FAILED, originalRequestId, rejected.code());
            case Submission.Blocked blocked -> new CallLogService.CallOutcome(
                    CallLog.Status.FAILED, originalRequestId, blocked.code());
            case Submission.Unknown unknown -> new CallLogService.CallOutcome(
                    CallLog.Status.UNKNOWN, originalRequestId, unknown.code());
            default -> CallLogService.CallOutcome.succeeded(originalRequestId);
        };
    }

    private void recordSubmission(Task task, String workerId, AttemptContext attempt,
            Submission result) {
        switch (result) {
            case Submission.Accepted accepted -> tasks.waitForProvider(task, workerId,
                    accepted.requestId(), Instant.now().plusSeconds(5));
            case Submission.Completed completed -> tasks.succeedWithArtifact(task, workerId,
                    archive(attempt, completed.payload()));
            case Submission.CompletedArtifact completed ->
                    tasks.succeedWithArtifact(task, workerId, completed.content());
            case Submission.Rejected rejected -> tasks.rejectSubmission(task, workerId,
                    rejected.code());
            case Submission.Unknown unknown -> {
                // 拿到「无法确认外部是否完成」的确定结论时就立即按原因码判定，不再让用户
                // 盯着「正在提交」空等整个租约。租约若已在调用期间过期（例如租约被配得
                // 短于客户端超时），兜底扫描会接手同一条记录，这里不把它当成错误抛出。
                if (!tasks.markSubmissionUnknown(task, workerId, unknown.code())) {
                    LOGGER.warn("Uncertain submission left to the recovery scan: lease expired");
                }
            }
            case Submission.Pending ignored -> throw new IllegalStateException(
                    "Submission cannot be pending without an accepted request ID");
            case Submission.Blocked ignored -> throw new IllegalStateException(
                    "Submission cannot be blocked after external submission");
        }
    }

    /** Archive bytes by task identity before committing the immutable artifact version. */
    private ObjectNode archive(AttemptContext attempt, MediaPayload payload) {
        Task task = attempt.lease();
        try (payload) {
            Asset asset = task.kind() == Task.Kind.IMAGE_GENERATION
                    ? assets.archiveTaskImage(attempt.ownerId(), task.projectId(), task.id(),
                            payload::stream)
                    : task.kind() == Task.Kind.AUDIO_GENERATION
                        ? assets.archiveTaskAudio(attempt.ownerId(), task.projectId(), task.id(), payload::stream)
                        : assets.archiveTaskVideo(attempt.ownerId(), task.projectId(), task.id(),
                            payload::stream);
            ObjectNode content = mapper.createObjectNode();
            content.put("assetId", asset.id().toString());
            content.put("prompt", task.input().path("prompt").asText());
            if (task.input().has("negativePrompt")) {
                content.put("negativePrompt", task.input().path("negativePrompt").asText());
            }
            content.put("sourceTaskId", task.id().toString());
            content.put("providerConfigVersion",
                    task.input().path("providerConfigVersion").asInt());
            content.put("workflowVersion", task.input().path("workflowVersion").asText());
            ObjectNode parameters = content.putObject("parameters");
            JsonNode frozenParameters = task.input().path("mediaInput").path("parameters");
            if (frozenParameters.isObject()) {
                frozenParameters.properties().forEach(entry ->
                        parameters.set(entry.getKey(), entry.getValue().deepCopy()));
            }
            parameters.put("adapterId", attempt.binding().adapterId());
            parameters.put("capabilityId", attempt.binding().capabilityId().toString());
            parameters.put("providerRequestId", attempt.originalRequestId() != null
                    ? attempt.originalRequestId() : attempt.requestKey());
            return content;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot close media response", failure);
        }
    }
}
