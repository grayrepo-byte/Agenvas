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
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition.OutputKind;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.VideoOperation;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final LocalVideoProcessor videoProcessor;

    public MediaExecutionWorker(TaskService tasks, MediaCapabilityService catalog,
            MediaAdapterRegistry adapters, AssetService assets, ObjectMapper mapper, CallLogService callLogs,
            LocalVideoProcessor videoProcessor) {
        this.callLogs = callLogs;
        this.tasks = tasks;
        this.catalog = catalog;
        this.adapters = adapters;
        this.assets = assets;
        this.mapper = mapper;
        this.videoProcessor = videoProcessor;
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
            UUID requestKey;
            try {
                requestKey = tasks.beginSubmission(task, workerId, binding);
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
                var savedManifest = tasks.providerResultManifest(task);
                result = savedManifest.isPresent() ? new Submission.CompletedResults(savedManifest.get())
                        : callLogs.record(descriptor(task, binding, CallLog.Operation.POLL),
                            () -> adapter.reconcile(attempt), resultValue -> callOutcome(resultValue, task.providerRequestId()));
            } catch (RuntimeException transientFailure) {
                String permanent = klingArchiveFailure(binding, transientFailure);
                if (permanent != null) { tasks.blockProviderPoll(task, workerId, permanent); continue; }
                tasks.retryProviderPoll(task, workerId, "PROVIDER_POLL_TECHNICAL_FAILURE");
                continue;
            }
            switch (result) {
                case Submission.Pending pending ->
                    tasks.deferProviderPoll(task, workerId, pending.nextActionAt());
                case Submission.Completed completed ->
                    finishPolledPayload(attempt, workerId, completed.payload());
                case Submission.CompletedArtifact completed ->
                    completeArtifact(attempt, workerId, completed.content());
                case Submission.CompletedResults completed ->
                    finishPolledManifest(attempt, workerId, adapter, completed.manifest());
                case Submission.Rejected rejected -> tasks.fail(task, workerId, rejected.code());
                case Submission.Blocked blocked -> tasks.blockProviderPoll(task, workerId,
                        blocked.code());
                case Submission.Unknown ignored ->
                    tasks.retryProviderPoll(task, workerId, "PROVIDER_POLL_TECHNICAL_FAILURE");
                case Submission.AcceptedResults ignored ->
                    tasks.retryProviderPoll(task, workerId, "PROVIDER_PROTOCOL_INVALID");
                case Submission.Accepted ignored ->
                    tasks.deferProviderPoll(task, workerId, Instant.now().plusSeconds(5));
            }
        }
        return claimed.size();
    }

    private String klingArchiveFailure(MediaCapabilityBinding binding, RuntimeException failure) {
        if (dev.agenvas.provider.domain.NativeMediaProfiles.supports(binding.adapterId())
                && failure instanceof dev.agenvas.provider.domain.NativeMediaFailure nativeFailure) return nativeFailure.code();
        if (!dev.agenvas.provider.domain.KlingModels.adapter(binding.adapterId())) return null;
        if (failure instanceof dev.agenvas.provider.infrastructure.KlingClient.CredentialUnavailable)
            return "MEDIA_CREDENTIAL_UNAVAILABLE";
        if (failure instanceof dev.agenvas.provider.infrastructure.KlingClient.TaskExpired) return "KLING_TASK_EXPIRED";
        if (failure instanceof dev.agenvas.provider.infrastructure.KlingClient.ProtocolFailure) return "PROTOCOL_INVALID";
        if (failure instanceof dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy.Expired) return "KLING_RESULT_EXPIRED";
        if (failure instanceof dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy.Rejected) return failure.getMessage();
        return null;
    }

    private void finishPolledPayload(AttemptContext attempt, String workerId, MediaPayload payload) {
        ObjectNode content;
        try {
            content = archive(attempt, payload);
        } catch (RuntimeException archiveFailure) {
            String permanent = klingArchiveFailure(attempt.binding(), archiveFailure);
            if (permanent != null) tasks.blockProviderPoll(attempt.lease(), workerId, permanent);
            else tasks.retryProviderPoll(attempt.lease(), workerId, "PROVIDER_POLL_TECHNICAL_FAILURE");
            return;
        }
        // Payload polling retries byte archival, but final Task write failures still propagate.
        completeArtifact(attempt, workerId, content);
    }

    private void finishPolledManifest(AttemptContext attempt, String workerId, MediaAdapter adapter,
            ProviderResultManifest manifest) {
        Task task = attempt.lease();
        try {
            if (tasks.providerResultManifest(task).isEmpty()) {
                tasks.checkpointProviderResults(task, workerId, manifest);
            }
            var results = archiveResults(attempt, adapter, manifest);
            tasks.succeedWithArtifacts(task, workerId, withSilentVideo(attempt, results), manifest.usage());
        } catch (RuntimeException archiveFailure) {
            String permanent = klingArchiveFailure(attempt.binding(), archiveFailure);
            if (permanent != null) tasks.blockProviderPoll(task, workerId, permanent);
            else tasks.retryProviderPoll(task, workerId, "PROVIDER_POLL_TECHNICAL_FAILURE");
        }
    }

    private CallLogService.CallDescriptor descriptor(Task task, MediaCapabilityBinding binding,
            CallLog.Operation operation) {
        var snapshot = catalog.pinnedSnapshot(binding);
        var spec = mapper.readTree(snapshot.specJson());
        String model = spec.path("settings").path("model").asText("");
        if (model.isEmpty()) model = spec.path("modelId").asText(null);
        if (model == null) model = spec.path("settings").path("workflowId").asText(null);
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
            case Submission.AcceptedResults accepted -> CallLogService.CallOutcome.succeeded(accepted.requestId());
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
            case Submission.AcceptedResults accepted -> tasks.waitForProviderWithResults(task, workerId, accepted.requestId(), accepted.manifest());
            case Submission.Accepted accepted -> tasks.waitForProvider(task, workerId,
                    accepted.requestId(), Instant.now().plusSeconds(5));
            case Submission.Completed completed -> completeArtifact(attempt, workerId,
                    archive(attempt, completed.payload()));
            case Submission.CompletedArtifact completed ->
                    completeArtifact(attempt, workerId, completed.content());
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
            case Submission.CompletedResults ignored -> throw new IllegalStateException("Results require an acknowledged provider task ID");
            case Submission.Pending ignored -> throw new IllegalStateException(
                    "Submission cannot be pending without an accepted request ID");
            case Submission.Blocked ignored -> throw new IllegalStateException(
                    "Submission cannot be blocked after external submission");
        }
    }

    private void completeArtifact(AttemptContext attempt, String workerId, tools.jackson.databind.JsonNode content) {
        Task task = attempt.lease();
        List<TaskService.ArchivedProviderResult> results;
        try {
            results = withSilentVideo(attempt, List.of(new TaskService.ArchivedProviderResult(0,
                    dev.agenvas.artifact.domain.Artifact.Kind.valueOf(task.kind().name().replace("_GENERATION", "")), true, content)));
        } catch (RuntimeException processingFailure) {
            if (attempt.originalRequestId() != null) tasks.retryProviderPoll(task, workerId, "LOCAL_VIDEO_PROCESSING_FAILED");
            else tasks.rejectSubmission(task, workerId, "LOCAL_VIDEO_PROCESSING_FAILED");
            return;
        }
        tasks.succeedWithArtifacts(task, workerId, results, null);
    }

    /** Cloud audio extraction also keeps a local, lossless silent copy of the original pinned video. */
    private List<TaskService.ArchivedProviderResult> withSilentVideo(AttemptContext attempt,
            List<TaskService.ArchivedProviderResult> results) {
        if (!attempt.lease().input().has(VideoOperation.SILENT_VIDEO_TARGET)) return results;
        var separated = new java.util.ArrayList<>(results);
        separated.add(new TaskService.ArchivedProviderResult(results.size(),
                dev.agenvas.artifact.domain.Artifact.Kind.VIDEO, false, videoProcessor.silentVideoResult(attempt)));
        return List.copyOf(separated);
    }

    private List<TaskService.ArchivedProviderResult> archiveResults(AttemptContext attempt, MediaAdapter adapter,
            ProviderResultManifest manifest) {
        List<TaskService.ArchivedProviderResult> archived = new java.util.ArrayList<>();
        try (var downloads = adapter.openResultDownloads(attempt)) {
            for (var result : manifest.results()) {
                // This is an archive identity, not a new Task or a new provider generation.
                UUID archiveId = UUID.nameUUIDFromBytes(("agenvas:provider-output:v1:" + attempt.lease().id() + ":" + result.ordinal())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Supplier<InputStream> input = () -> downloads.download(result).stream();
                Asset asset = archiveAsset(attempt, archiveId, result.kind(), input);
                ObjectNode content = resultContent(attempt, asset);
                content.withObject("parameters").put("providerOutputOrdinal", result.ordinal()).put("providerOutputNodeId", result.nodeId());
                content.withObject("parameters").set("providerUsage", manifest.usage());
                addNativeMetadata(attempt, content, result.providerMetadata());
                archived.add(new TaskService.ArchivedProviderResult(result.ordinal(),
                        dev.agenvas.artifact.domain.Artifact.Kind.valueOf(result.kind().name()), result.primary(), content));
            }
        }
        return List.copyOf(archived);
    }

    private Asset archiveAsset(AttemptContext attempt, UUID archiveId, OutputKind kind,
            Supplier<InputStream> input) {
        return switch (kind) {
            case IMAGE -> assets.archiveTaskImage(attempt.ownerId(), attempt.lease().projectId(), archiveId, input);
            case AUDIO -> assets.archiveTaskAudio(attempt.ownerId(), attempt.lease().projectId(), archiveId, input);
            case VIDEO -> assets.archiveTaskVideo(attempt.ownerId(), attempt.lease().projectId(), archiveId, input);
        };
    }

    private ObjectNode resultContent(AttemptContext attempt, Asset asset) {
        Task task = attempt.lease();
        ObjectNode content = MediaResult.content(mapper, task, asset.id(),
                task.input().path("prompt").asText());
        ObjectNode parameters = MediaResult.copyFrozenParameters(content, task);
        parameters.put("adapterId", attempt.binding().adapterId());
        parameters.put("capabilityId", attempt.binding().capabilityId().toString());
        if ((dev.agenvas.provider.domain.KlingModels.adapter(attempt.binding().adapterId())
                || dev.agenvas.provider.domain.NativeMediaProfiles.supports(attempt.binding().adapterId())) && asset.durationMs() != null)
            parameters.put("providerResultDurationMs", asset.durationMs());
        parameters.put("providerRequestId", attempt.originalRequestId() != null ? attempt.originalRequestId() : attempt.requestKey());
        return content;
    }

    /** Only provider result identity and layer annotations may enter public artifact content. */
    private void addNativeMetadata(AttemptContext attempt, ObjectNode content, tools.jackson.databind.JsonNode metadata) {
        if (metadata == null || !metadata.isObject() || !dev.agenvas.provider.domain.NativeMediaProfiles.supports(attempt.binding().adapterId())) return;
        ObjectNode safe = mapper.createObjectNode();
        String fileName = metadata.path("fileName").asText("");
        if ("GOOGLE_VEO".equals(attempt.binding().adapterId()) && fileName.matches("files/[A-Za-z0-9_-]{1,180}")) {
            safe.put("fileName", fileName).put("adapterId", attempt.binding().adapterId());
            var profile = catalog.nativeProfile(attempt.binding());
            safe.put("model", profile.profile().model()).put("connectionId", attempt.binding().connectionId().toString())
                    .put("connectionVersion", attempt.binding().connectionVersion());
            content.withObject("parameters").set("nativeProviderResult", safe);
        }
        if ("ARK_SEEDREAM_IMAGE".equals(attempt.binding().adapterId())) {
            for (String key : java.util.List.of("z_index", "name", "description", "bounding_box")) {
                var value = metadata.get(key);
                if (value != null && (value.isIntegralNumber() || value.isTextual() && value.asText().length() <= 4000
                        || "bounding_box".equals(key) && safeLayerBox(value))) safe.set(key, value);
            }
            if (!safe.isEmpty()) content.withObject("parameters").set("providerLayer", safe);
        }
    }

    private static boolean safeLayerBox(tools.jackson.databind.JsonNode value) {
        if (!value.isObject() || !value.propertyNames().equals(java.util.Set.of("absolute", "normalized"))) return false;
        for (String key : java.util.List.of("absolute", "normalized")) {
            var points = value.path(key);
            if (!points.isArray() || points.size() != 4) return false;
            for (var point : points) if (!point.isIntegralNumber() || !point.canConvertToInt() || point.asInt() < 0
                    || "normalized".equals(key) && point.asInt() > 1000) return false;
        }
        return true;
    }

    /** Archive bytes by task identity before committing the immutable artifact version. */
    private ObjectNode archive(AttemptContext attempt, MediaPayload payload) {
        Task task = attempt.lease();
        try (payload) {
            OutputKind kind = task.kind() == Task.Kind.IMAGE_GENERATION ? OutputKind.IMAGE
                    : task.kind() == Task.Kind.AUDIO_GENERATION ? OutputKind.AUDIO : OutputKind.VIDEO;
            Asset asset = archiveAsset(attempt, task.id(), kind, payload::stream);
            ObjectNode content = resultContent(attempt, asset);
            addNativeMetadata(attempt, content, payload.providerMetadata());
            return content;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot close media response", failure);
        }
    }
}
