package dev.agenvas.run.application;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.HexFormat;
import java.util.Base64;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Creates and advances persistent Runs under one database-owned active slot per project. */
@Service
public class AgentRunService {

    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private static final int MAX_INSTRUCTION_LENGTH = 20_000;
    private static final int MAX_SELECTED_ITEMS = 20;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProjectService projects;
    private final AgentInstanceService agents;
    private final ArtifactService artifacts;
    private final CanvasService canvas;
    private final AgentRunRepository runs;
    private final ProjectEventService events;
    private final RunTaskCancellation taskCancellation;
    private final RunTaskCreation taskCreation;
    private final UsageService usage;
    private final ChatGateway chatGateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentRunService(
            ProjectService projects,
            AgentInstanceService agents,
            ArtifactService artifacts,
            CanvasService canvas,
            AgentRunRepository runs,
            ProjectEventService events,
            RunTaskCancellation taskCancellation,
            RunTaskCreation taskCreation,
            UsageService usage,
            ChatGateway chatGateway,
            ObjectMapper objectMapper,
            Clock clock) {
        this.projects = projects;
        this.agents = agents;
        this.artifacts = artifacts;
        this.canvas = canvas;
        this.runs = runs;
        this.events = events;
        this.taskCancellation = taskCancellation;
        this.taskCreation = taskCreation;
        this.usage = usage;
        this.chatGateway = chatGateway;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Creates one queued Run or returns the original Run for an exact command replay. */
    @Transactional
    public CreateResult create(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            String requestedInstruction,
            String requestedIdempotencyKey) {
        return create(ownerId, projectId, agentId, requestedInstruction,
                requestedIdempotencyKey, null, null);
    }

    /** UI path pins the Agent configuration reviewed by the user before creating a Run. */
    @Transactional
    public CreateResult create(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            String requestedInstruction,
            String requestedIdempotencyKey,
            Long expectedAgentVersion) {
        return create(ownerId, projectId, agentId, requestedInstruction,
                requestedIdempotencyKey, expectedAgentVersion, null);
    }

    /** Optional scoped redo admits only an explicitly bound current shot version. */
    @Transactional
    public CreateResult create(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            String requestedInstruction,
            String requestedIdempotencyKey,
            Long expectedAgentVersion,
            UUID redoShotArtifactId) {
        return create(ownerId, projectId, agentId, requestedInstruction,
                requestedIdempotencyKey, expectedAgentVersion, redoShotArtifactId, List.of());
    }

    /** Pins optional UI selection as intent, without expanding the Agent's binding authority. */
    @Transactional
    public CreateResult create(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            String requestedInstruction,
            String requestedIdempotencyKey,
            Long expectedAgentVersion,
            UUID redoShotArtifactId,
            List<UUID> selectedItemIds) {
        return create(ownerId, projectId, agentId, requestedInstruction,
                requestedIdempotencyKey, expectedAgentVersion, redoShotArtifactId,
                selectedItemIds, null, null);
    }

    /** Pins the model configuration shown by the Run preflight consent panel. */
    @Transactional
    public CreateResult create(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            String requestedInstruction,
            String requestedIdempotencyKey,
            Long expectedAgentVersion,
            UUID redoShotArtifactId,
            List<UUID> selectedItemIds,
            String expectedModelConfigSource,
            Integer expectedModelConfigVersion) {
        String instruction = validateInstruction(requestedInstruction);
        String key = validateIdempotencyKey(requestedIdempotencyKey);
        List<UUID> selection = validateSelection(selectedItemIds);
        if ((expectedModelConfigSource == null) != (expectedModelConfigVersion == null)) {
            throw validation("模型配置来源和版本必须一起提交。");
        }
        String scope = "project:" + projectId + ":create-run";
        String requestFingerprint = agentId + "\n" + instruction + "\n"
                + expectedAgentVersion + "\n" + redoShotArtifactId + "\n" + selection;
        if (expectedModelConfigSource != null) {
            requestFingerprint += "\n" + expectedModelConfigSource + "\n"
                    + expectedModelConfigVersion;
        }
        String requestHash = sha256(requestFingerprint);
        Instant now = clock.instant();
        boolean reserved = runs.reserveIdempotency(
                ownerId, scope, key, requestHash, now.plus(IDEMPOTENCY_RETENTION), now);
        if (!reserved) {
            AgentRunRepository.IdempotencyRecord existing = runs.findIdempotency(ownerId, scope, key)
                    .orElseThrow(this::idempotencyInProgress);
            if (!existing.requestHash().equals(requestHash)) {
                throw new ApiProblemException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "幂等键已用于不同请求",
                        "请为不同的 Agent 或指令使用新的 Idempotency-Key。",
                        false);
            }
            if (existing.state() != AgentRunRepository.IdempotencyRecord.State.COMPLETED
                    || existing.resourceId() == null) {
                throw idempotencyInProgress();
            }
            return new CreateResult(
                    require(ownerId, projectId, existing.resourceId()), true);
        }

        Project project = projects.requireActiveProject(ownerId, projectId);
        AgentInstance agent = agents.get(ownerId, projectId, agentId);
        if (expectedAgentVersion != null && agent.version() != expectedAgentVersion) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "AGENT_VERSION_CONFLICT",
                    "Agent 配置已变化", "输入或指令可能已变化，请重新检查运行范围。", false);
        }
        ObjectNode policy = policySnapshot();
        if (expectedModelConfigSource != null
                && (!expectedModelConfigSource.equals(
                        policy.path("modelConfigSource").asText())
                    || expectedModelConfigVersion != policy.path("modelConfigVersion").asInt())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "MODEL_CONFIG_CONFLICT",
                    "模型配置已变化", "将使用的模型配置与运行前预览不同，请重新检查运行范围。", false);
        }
        UUID redoShotVersionId = null;
        if (redoShotArtifactId != null) {
            ArtifactService.ArtifactView shot = artifacts.get(ownerId, projectId,
                    redoShotArtifactId);
            if (shot.artifact().kind() != Artifact.Kind.SHOT
                    || agent.bindings().stream().noneMatch(binding ->
                            binding.artifactId().equals(redoShotArtifactId)
                            && binding.selectedVersionId().equals(
                                    shot.currentVersion().id()))) {
                throw validation("局部重做目标必须是 Agent 明确绑定的当前镜头版本。");
            }
            redoShotVersionId = shot.currentVersion().id();
        }
        UUID runId = UUID.randomUUID();
        AgentRun run = new AgentRun(
                runId,
                projectId,
                agentId,
                ownerId,
                AgentRun.Status.QUEUED,
                instruction,
                contextSnapshot(ownerId, agent, project, redoShotArtifactId,
                        redoShotVersionId, selection),
                policy,
                agent.profileVersion(),
                0,
                0,
                now,
                now,
                null);
        CreatedRun created = events.recordChange(ownerId, projectId, () -> {
                    projects.requireAvailableRunSlot(ownerId, projectId);
                    runs.create(run);
                    projects.assignRunSlot(ownerId, projectId, runId);
                    UUID firstTaskId = taskCreation.createInitialTurn(projectId, runId, now);
                    if (!runs.completeIdempotency(
                            ownerId,
                            scope,
                            key,
                            requestHash,
                            runId,
                            "{\"runId\":\"" + runId + "\"}",
                            now)) {
                        throw new IllegalStateException(
                                "Failed to complete reserved Run idempotency record");
                    }
                    return ProjectEventService.Change.changed(
                            new CreatedRun(run, firstTaskId), runEvent(run));
                })
                .value();
        ObjectNode taskPayload = objectMapper.createObjectNode();
        taskPayload.put("taskId", created.firstTaskId().toString());
        taskPayload.put("status", "READY");
        taskPayload.put("kind", "AGENT_TURN");
        events.append(ownerId, projectId, new ProjectEventService.EventDraft(
                "task.status.changed", 1, created.firstTaskId(), 0, taskPayload));
        return new CreateResult(created.run(), false);
    }

    /** Reads one nested Run without exposing another owner's existence. */
    @Transactional(readOnly = true)
    public AgentRun get(UUID ownerId, UUID projectId, UUID runId) {
        projects.get(ownerId, projectId);
        return require(ownerId, projectId, runId);
    }

    /** Pages an Agent's durable history without returning raw model turns or tool arguments. */
    @Transactional(readOnly = true)
    public RunPage list(UUID ownerId, UUID projectId, UUID agentId,
            String encodedCursor, Integer requestedLimit) {
        projects.get(ownerId, projectId);
        agents.get(ownerId, projectId, agentId);
        int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw validation("limit 必须在 1 到 100 之间。");
        }
        RunCursor cursor = decodeCursor(encodedCursor);
        List<AgentRun> rows = runs.list(ownerId, projectId, agentId,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(), limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AgentRun> items = hasMore ? rows.subList(0, limit) : rows;
        return new RunPage(List.copyOf(items),
                hasMore ? encodeCursor(items.getLast()) : null);
    }

    /** One history page within the requested Agent boundary. */
    public record RunPage(List<AgentRun> items, String nextCursor) {}

    private record RunCursor(Instant createdAt, UUID id) {}

    private RunCursor decodeCursor(String encoded) {
        if (encoded == null) {
            return null;
        }
        if (encoded.isBlank() || encoded.length() > 160) {
            throw validation("cursor 无效或已损坏。");
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.UTF_8);
            String[] parts = value.split(":", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("invalid cursor parts");
            }
            Instant createdAt = Instant.ofEpochSecond(
                    Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            return new RunCursor(createdAt, UUID.fromString(parts[2]));
        } catch (IllegalArgumentException | DateTimeException invalidCursor) {
            throw validation("cursor 无效或已损坏。");
        }
    }

    private String encodeCursor(AgentRun run) {
        String value = run.createdAt().getEpochSecond() + ":"
                + run.createdAt().getNano() + ":" + run.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Shows the exact current input bindings and policy before any planning-model call. */
    @Transactional(readOnly = true)
    public RunPreflight preflight(UUID ownerId, UUID projectId, UUID agentId) {
        projects.requireActiveProject(ownerId, projectId);
        AgentInstance agent = agents.get(ownerId, projectId, agentId);
        ChatGateway.ModelDetails model = chatGateway.modelDetails();
        return new RunPreflight(agent.id(), agent.version(), agent.name(),
                agent.instruction(), agent.bindings().stream()
                        .map(binding -> {
                            Artifact target = artifacts.get(ownerId, projectId,
                                    binding.artifactId()).artifact();
                            return new PreflightBinding(binding.artifactId(),
                                    binding.selectedVersionId(), target.title(), target.kind());
                        })
                        .toList(), model.available(), model.providerAdapter(),
                model.modelId(), model.toolCalling(), policySnapshot());
    }

    /** The preview never contains keys, endpoints or model-private messages. */
    public record RunPreflight(UUID agentId, long agentVersion, String agentName,
            String agentInstruction, List<PreflightBinding> bindings,
            boolean modelAvailable, String providerAdapter, String modelId,
            boolean toolCalling, ObjectNode policySnapshot) {}

    /** One immutable ArtifactVersion that would be serialized as first-turn JSON text. */
    public record PreflightBinding(UUID artifactId, UUID selectedVersionId,
            String artifactTitle, Artifact.Kind artifactKind) {}

    /** Applies one valid durable state transition and releases the slot on terminal states. */
    @Transactional
    public AgentRun transition(
            UUID ownerId,
            UUID projectId,
            UUID runId,
            long expectedVersion,
            AgentRun.Status target) {
        return events.recordChange(ownerId, projectId, () -> {
                    AgentRun current = runs.findForUpdate(ownerId, projectId, runId)
                            .orElseThrow(this::notFound);
                    if (current.version() != expectedVersion) {
                        throw versionConflict();
                    }
                    if (current.status() == target) {
                        return ProjectEventService.Change.unchanged(current);
                    }
                    if (!allowedTargets(current.status()).contains(target)) {
                        throw new ApiProblemException(
                                HttpStatus.CONFLICT,
                                "RUN_STATE_CONFLICT",
                                "Run 状态不可转换",
                                "不能从 " + current.status() + " 转换到 " + target + "。",
                                false);
                    }
                    AgentRun updated = updateStatusLocked(
                            ownerId, projectId, runId, expectedVersion, target);
                    if (target == AgentRun.Status.CANCEL_REQUESTED) {
                        cancelUnsubmittedTasks(ownerId, projectId, runId);
                        events.append(ownerId, projectId, runEvent(updated));
                        return ProjectEventService.Change.unchanged(updated);
                    }
                    return ProjectEventService.Change.changed(updated, runEvent(updated));
                })
                .value();
    }

    /** Advances the persisted model cursor after the next Task is created in one transaction. */
    @Transactional
    public AgentRun advanceStep(UUID ownerId, UUID projectId, UUID runId,
            long expectedVersion, int expectedStepIndex) {
        if (expectedStepIndex < 0 || expectedStepIndex >= 11) {
            throw validation("模型回合已达到默认上限。");
        }
        return events.recordChange(ownerId, projectId, () -> {
            AgentRun current = runs.findForUpdate(ownerId, projectId, runId)
                    .orElseThrow(this::notFound);
            if (current.version() != expectedVersion
                    || current.nextStepIndex() != expectedStepIndex
                    || (current.status() != AgentRun.Status.RUNNING
                            && current.status() != AgentRun.Status.WAITING_APPROVAL)
                    || !runs.advanceStep(ownerId, projectId, runId, expectedVersion,
                            expectedStepIndex, clock.instant())) {
                throw versionConflict();
            }
            AgentRun advanced = require(ownerId, projectId, runId);
            return ProjectEventService.Change.changed(advanced, runEvent(advanced));
        }).value();
    }

    /** Durably stops future Task work before releasing the Run's project slot. */
    @Transactional
    public AgentRun cancel(UUID ownerId, UUID projectId, UUID runId) {
        return events.recordChange(ownerId, projectId, () -> {
                    AgentRun current = runs.findForUpdate(ownerId, projectId, runId)
                            .orElseThrow(this::notFound);
                    if (current.status().terminal()) {
                        return ProjectEventService.Change.unchanged(current);
                    }
                    if (current.status() != AgentRun.Status.CANCEL_REQUESTED) {
                        current = updateStatusLocked(
                                ownerId,
                                projectId,
                                runId,
                                current.version(),
                                AgentRun.Status.CANCEL_REQUESTED);
                    }
                    cancelUnsubmittedTasks(ownerId, projectId, runId);
                    AgentRun canceled = updateStatusLocked(
                            ownerId,
                            projectId,
                            runId,
                            current.version(),
                            AgentRun.Status.CANCELED);
                    events.append(ownerId, projectId, runEvent(canceled));
                    return ProjectEventService.Change.unchanged(canceled);
                })
                .value();
    }

    /** Only PENDING/READY media Tasks returned by the cancellation update can be released. */
    private void cancelUnsubmittedTasks(UUID ownerId, UUID projectId, UUID runId) {
        for (Task task : taskCancellation.requestCancellation(projectId, runId,
                clock.instant())) {
            if (task.planId() != null) {
                usage.releaseUnsubmittedMediaTask(ownerId, task);
            }
        }
    }

    private AgentRun updateStatusLocked(
            UUID ownerId,
            UUID projectId,
            UUID runId,
            long expectedVersion,
            AgentRun.Status target) {
        Instant now = clock.instant();
        if (!runs.updateStatus(
                ownerId,
                projectId,
                runId,
                expectedVersion,
                target,
                now,
                target.terminal() ? now : null)) {
            throw versionConflict();
        }
        if (target.terminal()) {
            projects.releaseRunSlot(ownerId, projectId, runId);
        }
        return require(ownerId, projectId, runId);
    }

    private ProjectEventService.EventDraft runEvent(AgentRun run) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", run.status().name());
        payload.put("runId", run.id().toString());
        payload.put("nextStepIndex", run.nextStepIndex());
        return new ProjectEventService.EventDraft(
                "agent.run.changed", 1, run.id(), run.version(), payload);
    }

    private ObjectNode contextSnapshot(UUID ownerId, AgentInstance agent, Project project,
            UUID redoShotArtifactId, UUID redoShotVersionId, List<UUID> selectedItemIds) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("projectName", project.name());
        snapshot.put("aspectRatio", project.aspectRatio().name());
        snapshot.put("agentId", agent.id().toString());
        snapshot.put("agentVersion", agent.version());
        snapshot.put("profileKey", agent.profileKey());
        snapshot.put("profileVersion", agent.profileVersion());
        snapshot.put("agentName", agent.name());
        snapshot.put("agentInstruction", agent.instruction());
        snapshot.put("outputGroupId", agent.outputGroupId().toString());
        if (redoShotArtifactId != null) {
            snapshot.put("redoShotArtifactId", redoShotArtifactId.toString());
            snapshot.put("redoShotVersionId", redoShotVersionId.toString());
        }
        ArrayNode bindings = snapshot.putArray("bindings");
        for (AgentInstance.Binding binding : agent.bindings()) {
            if (redoShotArtifactId != null
                    && !redoShotArtifactId.equals(binding.artifactId())) continue;
            ObjectNode item = bindings.addObject();
            item.put("artifactId", binding.artifactId().toString());
            item.put("selectedVersionId", binding.selectedVersionId().toString());
            item.put("bindingType", binding.bindingType().name());
            ArtifactService.ArtifactView selected = artifacts.get(ownerId, project.id(),
                    binding.artifactId());
            item.put("kind", selected.artifact().kind().name());
            item.put("title", selected.artifact().title());
            if (selected.currentVersion().id().equals(binding.selectedVersionId())) {
                item.put("expectedVersion", selected.artifact().version());
            }
        }
        ArrayNode selection = snapshot.putArray("selection");
        List<CanvasService.CanvasEntry> items = selectedItemIds.isEmpty()
                ? List.of() : canvas.list(ownerId, project.id());
        for (UUID itemId : selectedItemIds) {
            CanvasService.CanvasEntry selected = items.stream()
                    .filter(entry -> entry.item().id().equals(itemId))
                    .findFirst().orElseThrow(() -> validation(
                            "选中卡片已不存在或不属于当前项目，请重新选择后运行。"));
            CanvasItem item = selected.item();
            ObjectNode reference = selection.addObject();
            reference.put("itemId", item.id().toString());
            reference.put("subjectType", item.subjectType().name());
            reference.put("subjectId", item.subjectId().toString());
            if (selected.artifact() != null) {
                reference.put("versionId", selected.artifact().currentVersion().id().toString());
                reference.put("kind", selected.artifact().artifact().kind().name());
            }
        }
        return snapshot;
    }

    private ObjectNode policySnapshot() {
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("schemaVersion", 1);
        ChatGateway.ConfigIdentity model = chatGateway.configIdentity();
        policy.put("modelConfigVersion", model.version());
        policy.put("modelConfigSource", model.source());
        policy.put("maxModelTurns", 12);
        policy.put("maxToolExecutions", 40);
        policy.put("maxImages", 8);
        policy.put("maxVideos", 6);
        policy.put("maxShots", 6);
        return policy;
    }

    private Set<AgentRun.Status> allowedTargets(AgentRun.Status current) {
        return switch (current) {
            case QUEUED -> Set.of(
                    AgentRun.Status.RUNNING,
                    AgentRun.Status.BLOCKED,
                    AgentRun.Status.CANCEL_REQUESTED,
                    AgentRun.Status.FAILED);
            case RUNNING -> Set.of(
                    AgentRun.Status.WAITING_APPROVAL,
                    AgentRun.Status.WAITING_TASKS,
                    AgentRun.Status.BLOCKED,
                    AgentRun.Status.CANCEL_REQUESTED,
                    AgentRun.Status.SUCCEEDED,
                    AgentRun.Status.FAILED);
            case WAITING_APPROVAL, WAITING_TASKS -> Set.of(
                    AgentRun.Status.RUNNING,
                    AgentRun.Status.WAITING_TASKS,
                    AgentRun.Status.BLOCKED,
                    AgentRun.Status.CANCEL_REQUESTED,
                    AgentRun.Status.FAILED);
            case BLOCKED -> Set.of(
                    AgentRun.Status.RUNNING,
                    AgentRun.Status.CANCEL_REQUESTED,
                    AgentRun.Status.FAILED);
            case CANCEL_REQUESTED -> Set.of(AgentRun.Status.CANCELED);
            case CANCELED, FAILED, SUCCEEDED -> Set.of();
        };
    }

    private AgentRun require(UUID ownerId, UUID projectId, UUID runId) {
        return runs.find(ownerId, projectId, runId).orElseThrow(this::notFound);
    }

    private String validateInstruction(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_INSTRUCTION_LENGTH) {
            throw validation("运行指令必须为 1 至 20000 个字符。");
        }
        return normalized;
    }

    /** Normalizes the bounded UI selection before hashing the idempotent Run request. */
    private List<UUID> validateSelection(List<UUID> requested) {
        if (requested == null) return List.of();
        if (requested.size() > MAX_SELECTED_ITEMS
                || requested.stream().anyMatch(Objects::isNull)
                || new HashSet<>(requested).size() != requested.size()) {
            throw validation("选中卡片最多 20 个，且不能重复或为空。");
        }
        return List.copyOf(requested);
    }

    private String validateIdempotencyKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw validation("Idempotency-Key 必须为 1 至 200 个字符。");
        }
        return normalized;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "Run 不存在",
                "Run 不存在或当前用户无权访问。",
                false);
    }

    private ApiProblemException versionConflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "RUN_VERSION_CONFLICT",
                "Run 状态已更新",
                "Run 已被其他执行器修改，请读取最新状态。",
                false);
    }

    private ApiProblemException idempotencyInProgress() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_IN_PROGRESS",
                "相同请求正在处理",
                "请稍后使用相同 Idempotency-Key 重试。",
                true);
    }

    private ApiProblemException validation(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "运行请求无效",
                detail,
                false);
    }

    /** Creation outcome distinguishes a fresh 202 response from an exact replay. */
    public record CreateResult(AgentRun run, boolean replayed) {}

    /** Run and its first Task committed under one project event sequence lock. */
    private record CreatedRun(AgentRun run, UUID firstTaskId) {}
}
