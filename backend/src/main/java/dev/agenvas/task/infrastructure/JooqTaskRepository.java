package dev.agenvas.task.infrastructure;

import static dev.agenvas.db.Tables.AGENT_RUN;
import static dev.agenvas.db.Tables.MEDIA_CAPABILITY;
import static dev.agenvas.db.Tables.MEDIA_CAPABILITY_VERSION;
import static dev.agenvas.db.Tables.MEDIA_PROVIDER_CONNECTION;
import static dev.agenvas.db.Tables.PROJECT;
import static dev.agenvas.db.Tables.PROVIDER_ATTEMPT;
import static dev.agenvas.db.Tables.PROVIDER_DISPATCH_GATE;
import static dev.agenvas.db.Tables.TASK;
import static dev.agenvas.db.Tables.TASK_ARTIFACT_TARGET;
import static dev.agenvas.db.Tables.TASK_DEPENDENCY;
import static dev.agenvas.db.Tables.TASK_LATE_RESULT;
import static dev.agenvas.db.Tables.TASK_MANUAL_REPLACEMENT;
import static dev.agenvas.db.Tables.TASK_PROVIDER_POLL_RETRY;

import dev.agenvas.db.tables.records.TaskManualReplacementRecord;
import dev.agenvas.db.tables.records.TaskRecord;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.RunTaskCancellation;
import dev.agenvas.run.application.RunTaskCreation;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.domain.ProviderAttempt;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.TaskOrigin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 任务队列实现；使用短事务 SKIP LOCKED 认领和 lease_epoch 隔离旧 Worker。 */
@Repository
public class JooqTaskRepository implements TaskRepository, RunTaskCancellation, RunTaskCreation {

    private static final int DEFAULT_PROJECT_MEDIA_CAPACITY = 3;

    /** 全局派发门禁是单行表，1 是唯一存在的门禁行。 */
    private static final short PROVIDER_DISPATCH_GATE_ID = 1;

    /** ComfyUI 适配器前缀；数据库用 LIKE 前缀匹配，Java 侧复用同一前缀做 startsWith。 */
    private static final String COMFY_ADAPTER_PREFIX = "COMFY_";

    /** 人工替代审计行固定写入的确认码，与数据库约束的取值一致。 */
    private static final String CONFIRMATION_CODE_ACCEPT_POSSIBLE_DUPLICATE_COST =
            "ACCEPT_POSSIBLE_DUPLICATE_COST";

    /** 执行任务行的条件更新、行锁与产物目标查询。 */
    private final DSLContext dsl;
    /** 将 JSONB 输入、输出映射为受控 JSON 树。 */
    private final ObjectMapper objectMapper;

    /** 注入任务队列查询上下文和 JSON 映射器。 */
    public JooqTaskRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public void bindMediaTask(UUID taskId, MediaCapabilityBinding binding) {
        int changed = dsl.update(TASK)
                .set(TASK.CAPABILITY_ID, binding.capabilityId())
                .set(TASK.CAPABILITY_VERSION, binding.capabilityVersion())
                .set(TASK.CONNECTION_ID, binding.connectionId())
                .set(TASK.CONNECTION_VERSION, binding.connectionVersion())
                .where(TASK.ID.eq(taskId))
                .and(TASK.KIND.in(Task.Kind.IMAGE_GENERATION.name(),
                        Task.Kind.VIDEO_GENERATION.name()))
                .and(TASK.CAPABILITY_ID.isNull())
                .and(TASK.STATUS.in(Task.Status.PENDING.name(), Task.Status.READY.name()))
                .execute();
        if (changed != 1) {
            throw new IllegalStateException("New media Task binding was not saved once");
        }
    }

    @Override
    public Optional<MediaCapabilityBinding> mediaBinding(UUID taskId) {
        return dsl.select(TASK.CONNECTION_ID, TASK.CONNECTION_VERSION, TASK.CAPABILITY_ID,
                        TASK.CAPABILITY_VERSION, MEDIA_CAPABILITY_VERSION.ADAPTER_ID,
                        MEDIA_CAPABILITY_VERSION.MAPPING_SHA256)
                .from(TASK)
                .join(MEDIA_CAPABILITY_VERSION)
                    .on(MEDIA_CAPABILITY_VERSION.CAPABILITY_ID.eq(TASK.CAPABILITY_ID)
                        .and(MEDIA_CAPABILITY_VERSION.VERSION.eq(TASK.CAPABILITY_VERSION)))
                .where(TASK.ID.eq(taskId))
                // ck_task_media_binding 保证四个绑定列同时为空或同时非空：
                // 能联结到版本行的任务一定有 connection_version。
                .fetchOptional(record -> new MediaCapabilityBinding(
                        record.get(TASK.CONNECTION_ID),
                        record.get(TASK.CONNECTION_VERSION),
                        record.get(TASK.CAPABILITY_ID),
                        record.get(TASK.CAPABILITY_VERSION),
                        record.get(MEDIA_CAPABILITY_VERSION.ADAPTER_ID),
                        record.get(MEDIA_CAPABILITY_VERSION.MAPPING_SHA256)));
    }

    @Override
    public boolean lockCurrentMediaBinding(MediaCapabilityBinding binding) {
        var capability = MEDIA_CAPABILITY.as("a");
        var connection = MEDIA_PROVIDER_CONNECTION.as("c");
        var capabilityVersion = MEDIA_CAPABILITY_VERSION.as("av");
        return dsl.select(capability.ID)
                .from(capability)
                .join(connection).on(connection.ID.eq(capability.CONNECTION_ID))
                .join(capabilityVersion).on(capabilityVersion.CAPABILITY_ID.eq(capability.ID)
                        .and(capabilityVersion.VERSION.eq(capability.CURRENT_VERSION)))
                .where(capability.ID.eq(binding.capabilityId()))
                .and(connection.ID.eq(binding.connectionId()))
                .and(capability.ENABLED.isTrue())
                .and(connection.ENABLED.isTrue())
                .and(capability.CURRENT_VERSION.eq(binding.capabilityVersion()))
                .and(connection.CURRENT_VERSION.eq(binding.connectionVersion()))
                .and(capabilityVersion.ADAPTER_ID.eq(binding.adapterId()))
                .and(capabilityVersion.MAPPING_SHA256.eq(binding.mappingSha256()))
                .forShare().of(capability, connection)
                .fetchOptional(capability.ID)
                .isPresent();
    }

    /** 所有者和项目联合授权后读取最多 100 条外部提交尝试，不读取文件或凭证。 */
    @Override
    public List<ProviderAttempt> listProviderAttempts(UUID ownerId, UUID projectId, UUID taskId) {
        return dsl.select(PROVIDER_ATTEMPT.ID, PROVIDER_ATTEMPT.TASK_ID, PROVIDER_ATTEMPT.STATUS,
                        PROVIDER_ATTEMPT.REQUEST_KEY, PROVIDER_ATTEMPT.CANDIDATE_REQUEST_ID,
                        PROVIDER_ATTEMPT.CANDIDATE_ORIGIN_SHA256,
                        PROVIDER_ATTEMPT.PROVIDER_REQUEST_ID, PROVIDER_ATTEMPT.CREATED_AT,
                        PROVIDER_ATTEMPT.UPDATED_AT)
                .from(PROVIDER_ATTEMPT)
                .join(TASK).on(TASK.ID.eq(PROVIDER_ATTEMPT.TASK_ID)
                        .and(TASK.PROJECT_ID.eq(PROVIDER_ATTEMPT.PROJECT_ID)))
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(PROJECT.OWNER_ID.eq(ownerId))
                .and(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.ID.eq(taskId))
                .orderBy(PROVIDER_ATTEMPT.CREATED_AT.desc(), PROVIDER_ATTEMPT.ID.desc())
                .limit(100)
                .fetch(record -> new ProviderAttempt(
                        record.get(PROVIDER_ATTEMPT.ID),
                        record.get(PROVIDER_ATTEMPT.TASK_ID),
                        ProviderAttempt.Status.valueOf(record.get(PROVIDER_ATTEMPT.STATUS)),
                        record.get(PROVIDER_ATTEMPT.REQUEST_KEY),
                        record.get(PROVIDER_ATTEMPT.CANDIDATE_REQUEST_ID),
                        record.get(PROVIDER_ATTEMPT.CANDIDATE_ORIGIN_SHA256),
                        record.get(PROVIDER_ATTEMPT.PROVIDER_REQUEST_ID),
                        record.get(PROVIDER_ATTEMPT.CREATED_AT).toInstant(),
                        record.get(PROVIDER_ATTEMPT.UPDATED_AT).toInstant()));
    }

    /** 查询一个 UNKNOWN 原任务已建立的人工替代关系。 */
    @Override
    public Optional<ManualReplacement> findManualReplacement(UUID projectId, UUID originalTaskId) {
        return dsl.selectFrom(TASK_MANUAL_REPLACEMENT)
                .where(TASK_MANUAL_REPLACEMENT.PROJECT_ID.eq(projectId))
                .and(TASK_MANUAL_REPLACEMENT.ORIGINAL_TASK_ID.eq(originalTaskId))
                .fetchOptional(this::manualReplacement);
    }

    /** 按用户、项目和幂等键查找人工重试记录，供同命令安全重放。 */
    @Override
    public Optional<ManualReplacement> findManualReplacementByKey(UUID projectId, UUID ownerId,
            String idempotencyKey) {
        return dsl.selectFrom(TASK_MANUAL_REPLACEMENT)
                .where(TASK_MANUAL_REPLACEMENT.PROJECT_ID.eq(projectId))
                .and(TASK_MANUAL_REPLACEMENT.APPROVED_BY_USER_ID.eq(ownerId))
                .and(TASK_MANUAL_REPLACEMENT.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(this::manualReplacement);
    }

    /** 从审计行恢复原任务、替代任务、批准用户及确认时的原版本。 */
    private ManualReplacement manualReplacement(TaskManualReplacementRecord row) {
        return new ManualReplacement(row.getProjectId(),
                row.getOriginalTaskId(),
                row.getReplacementTaskId(),
                row.getApprovedByUserId(),
                row.getOriginalTaskVersion(),
                row.getIdempotencyKey(),
                row.getCreatedAt().toInstant());
    }

    /** 保存用户承担重复费用风险的审计链接；唯一约束限制一项原任务只能替代一次。 */
    @Override
    public void createManualReplacement(ManualReplacement replacement) {
        int changed = dsl.insertInto(TASK_MANUAL_REPLACEMENT)
                .set(TASK_MANUAL_REPLACEMENT.ORIGINAL_TASK_ID, replacement.originalTaskId())
                .set(TASK_MANUAL_REPLACEMENT.PROJECT_ID, replacement.projectId())
                .set(TASK_MANUAL_REPLACEMENT.REPLACEMENT_TASK_ID, replacement.replacementTaskId())
                .set(TASK_MANUAL_REPLACEMENT.APPROVED_BY_USER_ID, replacement.approvedByUserId())
                .set(TASK_MANUAL_REPLACEMENT.ORIGINAL_TASK_VERSION, replacement.originalTaskVersion())
                .set(TASK_MANUAL_REPLACEMENT.IDEMPOTENCY_KEY, replacement.idempotencyKey())
                .set(TASK_MANUAL_REPLACEMENT.CONFIRMATION_CODE,
                        CONFIRMATION_CODE_ACCEPT_POSSIBLE_DUPLICATE_COST)
                .set(TASK_MANUAL_REPLACEMENT.CREATED_AT, utc(replacement.createdAt()))
                .execute();
        if (changed != 1) throw new IllegalStateException("Manual replacement was not inserted");
    }

    /** 按稳定 UUID 顺序返回任务的全部前置依赖。 */
    @Override
    public List<UUID> dependencyIds(UUID projectId, UUID taskId) {
        return dsl.select(TASK_DEPENDENCY.DEPENDS_ON_TASK_ID)
                .from(TASK_DEPENDENCY)
                .where(TASK_DEPENDENCY.PROJECT_ID.eq(projectId))
                .and(TASK_DEPENDENCY.TASK_ID.eq(taskId))
                .orderBy(TASK_DEPENDENCY.DEPENDS_ON_TASK_ID)
                .fetch(TASK_DEPENDENCY.DEPENDS_ON_TASK_ID);
    }

    /** 查询依赖指定任务的所有消费者，供人工替代前确认其均未启动。 */
    @Override
    public List<Task> dependentTasks(UUID projectId, UUID taskId) {
        return dsl.select(TASK.fields()).from(TASK)
                .join(TASK_DEPENDENCY).on(TASK_DEPENDENCY.PROJECT_ID.eq(TASK.PROJECT_ID)
                        .and(TASK_DEPENDENCY.TASK_ID.eq(TASK.ID)))
                .where(TASK_DEPENDENCY.PROJECT_ID.eq(projectId))
                .and(TASK_DEPENDENCY.DEPENDS_ON_TASK_ID.eq(taskId))
                .orderBy(TASK.CREATED_AT, TASK.ID)
                .fetch(row -> mapTask(row.into(TASK)));
    }

    /** 只把仍为 PENDING 的消费者改为依赖替代任务，并递增受影响版本。 */
    @Override
    public List<Task> rewirePendingDependents(UUID projectId, UUID originalTaskId,
            UUID replacementTaskId, Instant now) {
        int changed = dsl.update(TASK_DEPENDENCY)
                .set(TASK_DEPENDENCY.DEPENDS_ON_TASK_ID, replacementTaskId)
                .where(TASK_DEPENDENCY.PROJECT_ID.eq(projectId))
                .and(TASK_DEPENDENCY.DEPENDS_ON_TASK_ID.eq(originalTaskId))
                .and(DSL.exists(DSL.selectOne()
                        .from(TASK)
                        .where(TASK.ID.eq(TASK_DEPENDENCY.TASK_ID))
                        .and(TASK.PROJECT_ID.eq(TASK_DEPENDENCY.PROJECT_ID))
                        .and(TASK.STATUS.eq(Task.Status.PENDING.name()))))
                .execute();
        if (changed == 0) return List.of();
        int versioned = dsl.update(TASK)
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .set(TASK.UPDATED_AT, utc(now))
                .where(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.STATUS.eq(Task.Status.PENDING.name()))
                .and(DSL.exists(DSL.selectOne()
                        .from(TASK_DEPENDENCY)
                        .where(TASK_DEPENDENCY.PROJECT_ID.eq(TASK.PROJECT_ID))
                        .and(TASK_DEPENDENCY.TASK_ID.eq(TASK.ID))
                        .and(TASK_DEPENDENCY.DEPENDS_ON_TASK_ID.eq(replacementTaskId))))
                .execute();
        if (versioned != changed) {
            throw new IllegalStateException("Dependent version count changed during retry rewiring");
        }
        return dependentTasks(projectId, replacementTaskId);
    }

    /** 在创建 Run 的同一事务中插入首个 READY 模型回合任务。 */
    @Override
    public UUID createInitialTurn(UUID projectId, UUID runId, Instant now) {
        JsonNode input = objectMapper.createObjectNode().put("schemaVersion", 1)
                .put("stepIndex", 0);
        String inputHash;
        try {
            inputHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
        UUID taskId = UUID.randomUUID();
        create(new Task(taskId, projectId, runId, null, "agent-turn-0",
                Task.Kind.AGENT_TURN, Task.Status.READY, false, input, inputHash,
                null, null, null, 1, now, null, null, 0, 0,
                null, now, now, null), List.of());
        return taskId;
    }

    /** 插入任务及依赖边；调用方负责先校验项目、Run 和依赖均在同一作用域。 */
    @Override
    public void create(Task task, List<UUID> dependencyIds) {
        dsl.insertInto(TASK)
                .set(TASK.ID, task.id())
                .set(TASK.PROJECT_ID, task.projectId())
                .set(TASK.RUN_ID, task.runId())
                .set(TASK.PLAN_ID, task.planId())
                .set(TASK.STEP_KEY, task.stepKey())
                .set(TASK.KIND, task.kind().name())
                .set(TASK.STATUS, task.status().name())
                .set(TASK.INPUT_JSON, JSONB.valueOf(task.input().toString()))
                .set(TASK.INPUT_HASH, task.inputHash())
                .set(TASK.OUTPUT_JSON, (JSONB) null)
                .set(TASK.PROVIDER_ID, task.providerId())
                .set(TASK.PROVIDER_REQUEST_ID, (String) null)
                .set(TASK.ATTEMPT_NO, task.attemptNo())
                .set(TASK.NEXT_ACTION_AT, utc(task.nextActionAt()))
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.LEASE_EPOCH, task.leaseEpoch())
                .set(TASK.VERSION, task.version())
                .set(TASK.ERROR_CODE, (String) null)
                .set(TASK.CREATED_AT, utc(task.createdAt()))
                .set(TASK.UPDATED_AT, utc(task.updatedAt()))
                .set(TASK.COMPLETED_AT, (OffsetDateTime) null)
                .set(TASK.ORIGIN, task.runId() == null
                        ? (task.kind() == Task.Kind.MEDIA_EXPORT
                                ? TaskOrigin.PROJECT_EXPORT : TaskOrigin.USER_DIRECT).name()
                        : TaskOrigin.AGENT.name())
                .execute();
        for (UUID dependencyId : dependencyIds) {
            dsl.insertInto(TASK_DEPENDENCY)
                    .set(TASK_DEPENDENCY.PROJECT_ID, task.projectId())
                    .set(TASK_DEPENDENCY.TASK_ID, task.id())
                    .set(TASK_DEPENDENCY.DEPENDS_ON_TASK_ID, dependencyId)
                    .set(TASK_DEPENDENCY.REQUIRED_OUTPUT_KEY, (String) null)
                    .execute();
        }
    }

    /** 固定任务创建时的既有产物目标或新输出槽位，结果归档时使用该快照做 CAS。 */
    @Override
    public void createArtifactTarget(ArtifactTarget target) {
        int changed = dsl.insertInto(TASK_ARTIFACT_TARGET)
                .set(TASK_ARTIFACT_TARGET.TASK_ID, target.taskId())
                .set(TASK_ARTIFACT_TARGET.PROJECT_ID, target.projectId())
                .set(TASK_ARTIFACT_TARGET.ARTIFACT_ID, target.artifactId())
                .set(TASK_ARTIFACT_TARGET.EXPECTED_CURRENT_VERSION_ID,
                        target.expectedCurrentVersionId())
                .set(TASK_ARTIFACT_TARGET.EXPECTED_ARTIFACT_VERSION,
                        target.expectedArtifactVersion())
                .set(TASK_ARTIFACT_TARGET.OUTPUT_SLOT_KEY, target.outputSlotKey())
                .execute();
        if (changed != 1) {
            throw new IllegalStateException("Failed to bind Task Artifact target");
        }
    }

    /** 读取任务输出目标的固定产物版本或输出槽位。 */
    @Override
    public Optional<ArtifactTarget> findArtifactTarget(UUID taskId) {
        return dsl.selectFrom(TASK_ARTIFACT_TARGET)
                .where(TASK_ARTIFACT_TARGET.TASK_ID.eq(taskId))
                .fetchOptional(row -> new ArtifactTarget(
                        row.getTaskId(),
                        row.getProjectId(),
                        row.getArtifactId(),
                        row.getExpectedCurrentVersionId(),
                        row.getExpectedArtifactVersion(),
                        row.getOutputSlotKey()));
    }

    @Override
    public Optional<Task> findOccupyingMediaTask(UUID projectId, UUID artifactId) {
        return dsl.select(TASK.fields()).from(TASK)
                .join(TASK_ARTIFACT_TARGET).on(TASK_ARTIFACT_TARGET.TASK_ID.eq(TASK.ID))
                .where(TASK.PROJECT_ID.eq(projectId))
                .and(TASK_ARTIFACT_TARGET.ARTIFACT_ID.eq(artifactId))
                .and(TASK.STATUS.in(Task.Status.PENDING.name(), Task.Status.READY.name(),
                                Task.Status.RUNNING.name(), Task.Status.SUBMITTING.name(),
                                Task.Status.WAITING_PROVIDER.name(), Task.Status.UNKNOWN.name())
                        .or(TASK.STATUS.eq(Task.Status.BLOCKED.name())
                                .and(TASK.PROVIDER_REQUEST_ID.isNotNull())))
                // 已由「新建尝试」处理过的原任务不再占用卡片，否则卡片会被永久占住。
                .and(DSL.notExists(DSL.selectOne()
                        .from(TASK_MANUAL_REPLACEMENT)
                        .where(TASK_MANUAL_REPLACEMENT.ORIGINAL_TASK_ID.eq(TASK.ID))))
                .orderBy(TASK.CREATED_AT, TASK.ID)
                .limit(1)
                .fetchOptional(row -> mapTask(row.into(TASK)));
    }

    @Override
    public Optional<Task> findDirectByStepKey(UUID ownerId, UUID projectId, String stepKey) {
        return dsl.select(TASK.fields()).from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(PROJECT.OWNER_ID.eq(ownerId))
                .and(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.STEP_KEY.eq(stepKey))
                .and(TASK.ORIGIN.eq(TaskOrigin.USER_DIRECT.name()))
                .fetchOptional(row -> mapTask(row.into(TASK)));
    }

    @Override
    public List<Task> listDirectForArtifact(UUID ownerId, UUID projectId, UUID artifactId) {
        return dsl.select(TASK.fields()).from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .join(TASK_ARTIFACT_TARGET).on(TASK_ARTIFACT_TARGET.TASK_ID.eq(TASK.ID))
                .where(PROJECT.OWNER_ID.eq(ownerId))
                .and(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.ORIGIN.eq(TaskOrigin.USER_DIRECT.name()))
                .and(TASK_ARTIFACT_TARGET.ARTIFACT_ID.eq(artifactId))
                // 卡片只呈现仍有效的任务：已被「重试」取代的原任务不再决定
                // 卡片是否可再次运行。
                .and(DSL.notExists(DSL.selectOne()
                        .from(TASK_MANUAL_REPLACEMENT)
                        .where(TASK_MANUAL_REPLACEMENT.ORIGINAL_TASK_ID.eq(TASK.ID))))
                .orderBy(TASK.CREATED_AT.desc(), TASK.ID.desc())
                .limit(50)
                .fetch(row -> mapTask(row.into(TASK)));
    }

    @Override
    public List<Task> listActiveDirect(UUID ownerId, UUID projectId) {
        return dsl.select(TASK.fields()).from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(PROJECT.OWNER_ID.eq(ownerId))
                .and(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.ORIGIN.eq(TaskOrigin.USER_DIRECT.name()))
                .and(TASK.STATUS.in(Task.Status.PENDING.name(), Task.Status.READY.name(),
                        Task.Status.RUNNING.name(), Task.Status.SUBMITTING.name(),
                        Task.Status.WAITING_PROVIDER.name(), Task.Status.UNKNOWN.name(),
                        Task.Status.BLOCKED.name()))
                .orderBy(TASK.CREATED_AT, TASK.ID)
                .limit(100)
                .fetch(row -> mapTask(row.into(TASK)));
    }

    @Override
    public QueueStatus queueStatus(UUID taskId) {
        Task task = findById(taskId).orElseThrow();
        if (task.status() != Task.Status.READY) return new QueueStatus(0, "NOT_QUEUED");
        var queued = TASK.as("queued");
        var current = TASK.as("current");
        long ahead = dsl.selectCount()
                .from(queued)
                .join(current).on(current.ID.eq(taskId))
                .where(queued.PROJECT_ID.eq(task.projectId()))
                .and(queued.STATUS.eq(Task.Status.READY.name()))
                .and(queued.KIND.in(Task.Kind.IMAGE_GENERATION.name(),
                        Task.Kind.VIDEO_GENERATION.name()))
                .and(DSL.row(queued.NEXT_ACTION_AT, queued.CREATED_AT, queued.ID)
                        .lt(DSL.row(current.NEXT_ACTION_AT, current.CREATED_AT, current.ID)))
                .fetchSingle().value1().longValue();
        var limits = dsl.select(TASK.CAPABILITY_ID, MEDIA_CAPABILITY.MAX_CONCURRENT,
                        MEDIA_CAPABILITY_VERSION.ADAPTER_ID)
                .from(TASK)
                .join(MEDIA_CAPABILITY).on(MEDIA_CAPABILITY.ID.eq(TASK.CAPABILITY_ID))
                .join(MEDIA_CAPABILITY_VERSION)
                    .on(MEDIA_CAPABILITY_VERSION.CAPABILITY_ID.eq(TASK.CAPABILITY_ID)
                        .and(MEDIA_CAPABILITY_VERSION.VERSION.eq(TASK.CAPABILITY_VERSION)))
                .where(TASK.ID.eq(taskId))
                .fetchSingle(record -> new QueueLimits(
                        record.get(TASK.CAPABILITY_ID),
                        record.get(MEDIA_CAPABILITY.MAX_CONCURRENT),
                        record.get(MEDIA_CAPABILITY_VERSION.ADAPTER_ID)));
        var active = TASK.as("active");
        long projectOccupied = occupiedCount(active,
                active.PROJECT_ID.eq(task.projectId()));
        long capabilityOccupied = occupiedCount(active,
                active.CAPABILITY_ID.eq(limits.capabilityId()));
        long comfyOccupied = dsl.selectCount()
                .from(active)
                .join(MEDIA_CAPABILITY_VERSION)
                    .on(MEDIA_CAPABILITY_VERSION.CAPABILITY_ID.eq(active.CAPABILITY_ID)
                        .and(MEDIA_CAPABILITY_VERSION.VERSION.eq(active.CAPABILITY_VERSION)))
                .where(MEDIA_CAPABILITY_VERSION.ADAPTER_ID
                        .like(COMFY_ADAPTER_PREFIX + "%"))
                .and(occupiedMedia(active))
                .fetchSingle().value1().longValue();
        String reason = projectOccupied >= DEFAULT_PROJECT_MEDIA_CAPACITY
                ? "PROJECT_CAPACITY" : capabilityOccupied >= limits.maxConcurrent()
                ? "CAPABILITY_CAPACITY" : limits.adapterId().startsWith(COMFY_ADAPTER_PREFIX)
                && comfyOccupied > 0 ? "COMFY_SINGLE_SLOT" : "WAITING_WORKER";
        return new QueueStatus(ahead, reason);
    }

    private long occupiedCount(dev.agenvas.db.tables.Task active, Condition scope) {
        return dsl.selectCount()
                .from(active)
                .where(scope)
                .and(occupiedMedia(active))
                .fetchSingle().value1().longValue();
    }

    private record QueueLimits(UUID capabilityId, int maxConcurrent, String adapterId) {}

    @Override
    public boolean cancelQueuedDirect(UUID projectId, UUID taskId, Instant now) {
        return dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.CANCELED.name())
                .set(TASK.CANCEL_REQUESTED, true)
                .set(TASK.COMPLETED_AT, utc(now))
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(taskId))
                .and(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.ORIGIN.eq(TaskOrigin.USER_DIRECT.name()))
                .and(TASK.STATUS.eq(Task.Status.READY.name()))
                .and(TASK.PROVIDER_REQUEST_ID.isNull())
                .execute() == 1;
    }

    /** 按所有者、项目和任务 ID 读取，避免暴露其他项目资源。 */
    @Override
    public Optional<Task> find(UUID ownerId, UUID projectId, UUID taskId) {
        return dsl.select(TASK.fields()).from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(TASK.ID.eq(taskId))
                .and(TASK.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .fetchOptional(row -> mapTask(row.into(TASK)));
    }

    /** 内部状态机按 ID 读取任务；该方法本身不执行 API 权限检查。 */
    @Override
    public Optional<Task> findById(UUID taskId) {
        return dsl.selectFrom(TASK)
                .where(TASK.ID.eq(taskId))
                .fetchOptional(this::mapTask);
    }

    /** 按项目命令键定位导出任务，用于校验幂等请求载荷。 */
    @Override
    public Optional<Task> findExportByStepKey(UUID ownerId, UUID projectId, String stepKey) {
        return dsl.select(TASK.fields()).from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(TASK.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .and(TASK.KIND.eq(Task.Kind.MEDIA_EXPORT.name()))
                .and(TASK.STEP_KEY.eq(stepKey))
                .fetchOptional(row -> mapTask(row.into(TASK)));
    }

    /** 返回最近 100 条项目导出，即使创建它们的 Run 已结束。 */
    @Override
    public List<Task> listExports(UUID ownerId, UUID projectId) {
        return dsl.select(TASK.fields()).from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(TASK.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .and(TASK.KIND.eq(Task.Kind.MEDIA_EXPORT.name()))
                .orderBy(TASK.CREATED_AT.desc(), TASK.ID.desc())
                .limit(100)
                .fetch(row -> mapTask(row.into(TASK)));
    }

    /** READY 导出立即转 CANCELED；RUNNING 导出只记录取消请求供 Worker 停止。 */
    @Override
    public boolean requestExportCancellation(UUID projectId, UUID taskId, Instant now) {
        return dsl.update(TASK)
                .set(TASK.CANCEL_REQUESTED, true)
                .set(TASK.STATUS, DSL.when(TASK.STATUS.eq(Task.Status.READY.name()),
                                Task.Status.CANCELED.name())
                        .otherwise(TASK.STATUS))
                .set(TASK.COMPLETED_AT, DSL.when(TASK.STATUS.eq(Task.Status.READY.name()),
                                DSL.val(utc(now), TASK.COMPLETED_AT))
                        .otherwise(TASK.COMPLETED_AT))
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(taskId))
                .and(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.KIND.eq(Task.Kind.MEDIA_EXPORT.name()))
                .and(TASK.STATUS.in(Task.Status.READY.name(), Task.Status.RUNNING.name()))
                .and(TASK.CANCEL_REQUESTED.isFalse())
                .execute() == 1;
    }

    /** 从数据库反查任务项目的权威所有者，不能使用模型输入中的 ownerId。 */
    @Override
    public Optional<UUID> ownerId(UUID taskId) {
        return dsl.select(PROJECT.OWNER_ID)
                .from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(TASK.ID.eq(taskId))
                .fetchOptional(PROJECT.OWNER_ID);
    }

    /** 按创建时间和 ID 稳定排序读取指定所有者 Run 的任务。 */
    @Override
    public List<Task> listByRun(UUID ownerId, UUID projectId, UUID runId) {
        return dsl.select(TASK.fields()).from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.RUN_ID.eq(runId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .orderBy(TASK.CREATED_AT, TASK.ID)
                .fetch(row -> mapTask(row.into(TASK)));
    }

    /** 返回项目内最近 100 个 UNKNOWN 任务，不依赖活动 Run 槽位。 */
    @Override
    public List<Task> listUnknown(UUID ownerId, UUID projectId) {
        return dsl.select(TASK.fields()).from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(TASK.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .and(TASK.STATUS.eq(Task.Status.UNKNOWN.name()))
                .orderBy(TASK.UPDATED_AT.desc(), TASK.ID)
                .limit(100)
                .fetch(row -> mapTask(row.into(TASK)));
    }

    /** 统计指定 Run 的任务类别数量，调用方须已锁定 Run 以防预算并发超额。 */
    @Override
    public long countByRunAndKind(UUID projectId, UUID runId, Task.Kind kind) {
        return dsl.selectCount()
                .from(TASK)
                .where(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.RUN_ID.eq(runId))
                .and(TASK.KIND.eq(kind.name()))
                .fetchSingle().value1().longValue();
    }

    /** 认领任意到期非 Agent 任务；Agent 回合留给独立调度器。 */
    @Override
    public List<Task> claimDue(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil, null);
    }

    @Override
    public List<Task> claimDueBoundMedia(String workerId, int limit, Instant now,
            Instant leaseUntil) {
        // The same database gate coordinates fixed ComfyUI requests across app instances.
        dsl.select(PROVIDER_DISPATCH_GATE.ID)
                .from(PROVIDER_DISPATCH_GATE)
                .where(PROVIDER_DISPATCH_GATE.ID.eq(PROVIDER_DISPATCH_GATE_ID))
                .forUpdate()
                .fetchSingle();
        var active = TASK.as("active");
        var capability = MEDIA_CAPABILITY.as("c");
        var capabilityVersion = MEDIA_CAPABILITY_VERSION.as("av");
        var activeCapabilityVersion = MEDIA_CAPABILITY_VERSION.as("active_av");
        Field<Integer> projectOccupied = DSL.field(DSL.selectCount()
                .from(active)
                .where(active.PROJECT_ID.eq(TASK.PROJECT_ID))
                .and(occupiedMedia(active)));
        Field<Integer> capabilityOccupied = DSL.field(DSL.selectCount()
                .from(active)
                .where(active.CAPABILITY_ID.eq(TASK.CAPABILITY_ID))
                .and(occupiedMedia(active)));
        Field<Integer> maxConcurrent = DSL.field(DSL.select(capability.MAX_CONCURRENT)
                .from(capability)
                .where(capability.ID.eq(TASK.CAPABILITY_ID)));
        return claimDueKind(workerId, limit, now, leaseUntil, null,
                TASK.CAPABILITY_ID.isNotNull()
                        .and(TASK.KIND.in(Task.Kind.IMAGE_GENERATION.name(),
                                Task.Kind.VIDEO_GENERATION.name()))
                        .and(projectOccupied.lt(DEFAULT_PROJECT_MEDIA_CAPACITY))
                        .and(capabilityOccupied.lt(maxConcurrent))
                        .and(DSL.notExists(DSL.selectOne()
                                        .from(capabilityVersion)
                                        .where(capabilityVersion.CAPABILITY_ID
                                                .eq(TASK.CAPABILITY_ID))
                                        .and(capabilityVersion.VERSION
                                                .eq(TASK.CAPABILITY_VERSION))
                                        .and(capabilityVersion.ADAPTER_ID
                                                .like(COMFY_ADAPTER_PREFIX + "%")))
                                .or(DSL.notExists(DSL.selectOne()
                                        .from(active)
                                        .join(activeCapabilityVersion)
                                            .on(activeCapabilityVersion.CAPABILITY_ID
                                                    .eq(active.CAPABILITY_ID)
                                                .and(activeCapabilityVersion.VERSION
                                                        .eq(active.CAPABILITY_VERSION)))
                                        .where(activeCapabilityVersion.ADAPTER_ID
                                                .like(COMFY_ADAPTER_PREFIX + "%"))
                                        .and(occupiedMedia(active))))));
    }

    /**
     * 活动媒体任务的占用条件：提交中、等待外部结果、未确认和已受理的阻断任务都占用名额。
     * UNKNOWN 一旦被人工重试取代就让出名额给替代任务，否则原任务会永久阻塞该项目与能力。
     *
     * @param active 已按 {@code active} 别名引用的 task 表；用全限定类型名避免与领域 Task 冲突
     */
    private static Condition occupiedMedia(dev.agenvas.db.tables.Task active) {
        return active.KIND.in(Task.Kind.IMAGE_GENERATION.name(),
                        Task.Kind.VIDEO_GENERATION.name())
                .and(DSL.or(active.STATUS.in(Task.Status.SUBMITTING.name(),
                                        Task.Status.WAITING_PROVIDER.name()),
                        active.STATUS.eq(Task.Status.UNKNOWN.name())
                                .and(DSL.notExists(DSL.selectOne()
                                        .from(TASK_MANUAL_REPLACEMENT)
                                        .where(TASK_MANUAL_REPLACEMENT.ORIGINAL_TASK_ID
                                                .eq(active.ID)))),
                        active.STATUS.eq(Task.Status.RUNNING.name())
                                .and(active.LEASE_UNTIL.gt(DSL.currentOffsetDateTime())),
                        active.STATUS.eq(Task.Status.BLOCKED.name())
                                .and(active.PROVIDER_REQUEST_ID.isNotNull())));
    }

    @Override
    public List<Task> claimDueBoundMediaPolls(String workerId, int limit, Instant now,
            Instant leaseUntil) {
        return claimProviderPolls(workerId, limit, now, leaseUntil, null,
                TASK.CAPABILITY_ID.isNotNull());
    }

    /** 仅认领 Mock 图片适配器可处理的到期图片任务。 */
    @Override
    public List<Task> claimDueImages(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil,
                Task.Kind.IMAGE_GENERATION, TASK.CAPABILITY_ID.isNull());
    }

    /** 通过数据库单槽门禁认领一个 ComfyUI 图片任务。 */
    @Override
    public List<Task> claimDueComfyImage(String workerId, Instant now, Instant leaseUntil) {
        return claimDueComfy(workerId, now, leaseUntil, Task.Kind.IMAGE_GENERATION);
    }

    /** 通过与图片共用的数据库单槽门禁认领一个 ComfyUI 视频任务。 */
    @Override
    public List<Task> claimDueComfyVideo(String workerId, Instant now, Instant leaseUntil) {
        return claimDueComfy(workerId, now, leaseUntil, Task.Kind.VIDEO_GENERATION);
    }

    /**
     * 锁定全局派发门禁；任一 ComfyUI 请求仍活动或未被取代的 UNKNOWN 时不提交新请求。
     * 这里比 {@link #occupiedMedia} 更保守（已受理的 RUNNING 也计入），但“已被替代的
     * UNKNOWN 不占名额”这条规则必须与它保持一致，否则重试后的替代任务会被原任务永久阻塞。
     */
    private List<Task> claimDueComfy(String workerId, Instant now, Instant leaseUntil,
            Task.Kind kind) {
        dsl.select(PROVIDER_DISPATCH_GATE.ID)
                .from(PROVIDER_DISPATCH_GATE)
                .where(PROVIDER_DISPATCH_GATE.ID.eq(PROVIDER_DISPATCH_GATE_ID))
                .forUpdate()
                .fetchSingle();
        long occupied = dsl.selectCount()
                .from(TASK)
                .where(TASK.KIND.in(Task.Kind.IMAGE_GENERATION.name(),
                        Task.Kind.VIDEO_GENERATION.name()))
                .and(DSL.or(TASK.STATUS.in(Task.Status.SUBMITTING.name(),
                                Task.Status.WAITING_PROVIDER.name()),
                        TASK.STATUS.eq(Task.Status.UNKNOWN.name())
                                .and(DSL.notExists(DSL.selectOne()
                                        .from(TASK_MANUAL_REPLACEMENT)
                                        .where(TASK_MANUAL_REPLACEMENT.ORIGINAL_TASK_ID
                                                .eq(TASK.ID)))),
                        TASK.STATUS.eq(Task.Status.BLOCKED.name())
                                .and(TASK.PROVIDER_REQUEST_ID.isNotNull()),
                        TASK.STATUS.eq(Task.Status.RUNNING.name())
                                .and(TASK.PROVIDER_REQUEST_ID.isNotNull()
                                        .or(TASK.LEASE_UNTIL.gt(utc(now))))))
                .fetchSingle().value1().longValue();
        return occupied == 0
                ? claimDueKind(workerId, 1, now, leaseUntil, kind, TASK.CAPABILITY_ID.isNull())
                : List.of();
    }

    /** 只认领到期视频任务，避免 Mock 图片路径误消费视频。 */
    @Override
    public List<Task> claimDueVideos(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil,
                Task.Kind.VIDEO_GENERATION, TASK.CAPABILITY_ID.isNull());
    }

    /** 认领持有已确认 provider_request_id 的图片或视频查询任务，不会选新提交。 */
    @Override
    public List<Task> claimDueProviderPolls(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimProviderPolls(workerId, limit, now, leaseUntil, null);
    }

    /** 限定 ComfyUI 图片轮询器只接管图片任务。 */
    @Override
    public List<Task> claimDueComfyImagePolls(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimProviderPolls(workerId, limit, now, leaseUntil,
                Task.Kind.IMAGE_GENERATION, TASK.CAPABILITY_ID.isNull());
    }

    /** 限定 ComfyUI 视频轮询器只接管视频任务。 */
    @Override
    public List<Task> claimDueComfyVideoPolls(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimProviderPolls(workerId, limit, now, leaseUntil,
                Task.Kind.VIDEO_GENERATION, TASK.CAPABILITY_ID.isNull());
    }

    /** 只按既有请求 ID 选取等待或租约过期的任务，并递增 fencing epoch。 */
    private List<Task> claimProviderPolls(String workerId, int limit, Instant now,
            Instant leaseUntil, Task.Kind onlyKind) {
        return claimProviderPolls(workerId, limit, now, leaseUntil, onlyKind, DSL.noCondition());
    }

    private List<Task> claimProviderPolls(String workerId, int limit, Instant now,
            Instant leaseUntil, Task.Kind onlyKind, Condition bindingClause) {
        Condition kindCondition = onlyKind == null
                ? TASK.KIND.in(Task.Kind.IMAGE_GENERATION.name(),
                        Task.Kind.VIDEO_GENERATION.name())
                : TASK.KIND.eq(onlyKind.name());
        Condition due = TASK.STATUS.eq(Task.Status.WAITING_PROVIDER.name())
                        .and(TASK.NEXT_ACTION_AT.le(utc(now)))
                .or(TASK.STATUS.eq(Task.Status.RUNNING.name())
                        .and(TASK.LEASE_UNTIL.le(utc(now))));
        var candidates = DSL.select(TASK.ID)
                .from(TASK)
                .where(kindCondition)
                .and(bindingClause)
                .and(TASK.PROVIDER_REQUEST_ID.isNotNull())
                .and(due)
                .orderBy(TASK.NEXT_ACTION_AT, TASK.CREATED_AT, TASK.ID)
                .limit(limit)
                .forUpdate().skipLocked()
                .asTable("c");
        return dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.RUNNING.name())
                .set(TASK.LEASE_OWNER, workerId)
                .set(TASK.LEASE_UNTIL, utc(leaseUntil))
                .set(TASK.LEASE_EPOCH, TASK.LEASE_EPOCH.plus(1))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .set(TASK.UPDATED_AT, utc(now))
                .from(candidates)
                .where(TASK.ID.eq(candidates.field(TASK.ID)))
                .returning(TASK.fields())
                .fetch(row -> mapTask(row.into(TASK)));
    }

    /** 只认领未取消、项目仍活动且 Run 为空的项目级导出任务。 */
    @Override
    public List<Task> claimDueExports(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        Condition due = TASK.STATUS.eq(Task.Status.READY.name())
                        .and(TASK.NEXT_ACTION_AT.le(utc(now)))
                .or(TASK.STATUS.eq(Task.Status.RUNNING.name())
                        .and(TASK.LEASE_UNTIL.le(utc(now))));
        var candidates = DSL.select(TASK.ID)
                .from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(TASK.KIND.eq(Task.Kind.MEDIA_EXPORT.name()))
                .and(TASK.RUN_ID.isNull())
                .and(PROJECT.STATUS.eq(Project.Status.ACTIVE.name()))
                .and(TASK.CANCEL_REQUESTED.isFalse())
                .and(due)
                .orderBy(TASK.NEXT_ACTION_AT, TASK.CREATED_AT, TASK.ID)
                .limit(limit)
                // 只锁 task 行：项目行可能被其他事务占用，锁它会让导出认领互相阻塞。
                .forUpdate().of(TASK).skipLocked()
                .asTable("c");
        return dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.RUNNING.name())
                .set(TASK.LEASE_OWNER, workerId)
                .set(TASK.LEASE_UNTIL, utc(leaseUntil))
                .set(TASK.LEASE_EPOCH, TASK.LEASE_EPOCH.plus(1))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .set(TASK.UPDATED_AT, utc(now))
                .from(candidates)
                .where(TASK.ID.eq(candidates.field(TASK.ID)))
                .returning(TASK.fields())
                .fetch(row -> mapTask(row.into(TASK)));
    }

    /** 单独认领 Agent 回合，Run 状态和恢复计划条件在 SQL 中再次限定。 */
    @Override
    public List<Task> claimDueAgentTurns(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil, Task.Kind.AGENT_TURN);
    }

    @Override
    public List<Task> claimDueTextGenerations(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil, Task.Kind.TEXT_GENERATION);
    }

    @Override
    public boolean checkpointTextResponse(UUID taskId, String workerId, long leaseEpoch,
            JsonNode response, Instant now) {
        return dsl.update(TASK)
                .set(TASK.OUTPUT_JSON, JSONB.valueOf(response.toString()))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .set(TASK.UPDATED_AT, utc(now))
                .where(TASK.ID.eq(taskId))
                .and(TASK.KIND.eq(Task.Kind.TEXT_GENERATION.name()))
                .and(TASK.STATUS.eq(Task.Status.RUNNING.name()))
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(leaseEpoch))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .and(TASK.CANCEL_REQUESTED.isFalse())
                .and(TASK.OUTPUT_JSON.isNull())
                .execute() == 1;
    }

    /** 在业务事务内锁住任务行，核验项目、Run、Worker、epoch、取消和租约期限。 */
    @Override
    public boolean lockActiveAgentTurnLease(UUID projectId, UUID runId, UUID taskId,
            String workerId, long leaseEpoch, Instant now) {
        return dsl.select(TASK.ID)
                .from(TASK)
                .where(TASK.ID.eq(taskId))
                .and(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.RUN_ID.eq(runId))
                .and(TASK.KIND.eq(Task.Kind.AGENT_TURN.name()))
                .and(TASK.STATUS.eq(Task.Status.RUNNING.name()))
                .and(TASK.CANCEL_REQUESTED.isFalse())
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(leaseEpoch))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .forUpdate()
                .fetchOptional(TASK.ID)
                .isPresent();
    }

    /** 模型与媒体使用互斥认领域；认领时递增 epoch 并返回更新后完整任务行。 */
    private List<Task> claimDueKind(String workerId, int limit, Instant now,
            Instant leaseUntil, Task.Kind onlyKind) {
        return claimDueKind(workerId, limit, now, leaseUntil, onlyKind, DSL.noCondition());
    }

    private List<Task> claimDueKind(String workerId, int limit, Instant now,
            Instant leaseUntil, Task.Kind onlyKind, Condition bindingClause) {
        boolean agentTurn = onlyKind == Task.Kind.AGENT_TURN;
        Condition kindCondition = onlyKind == null
                ? TASK.KIND.ne(Task.Kind.AGENT_TURN.name())
                : TASK.KIND.eq(onlyKind.name());
        Condition runCondition = agentTurn
                ? AGENT_RUN.STATUS.in(AgentRun.Status.QUEUED.name(),
                                AgentRun.Status.RUNNING.name())
                        .or(AGENT_RUN.STATUS.in(AgentRun.Status.WAITING_APPROVAL.name(),
                                        AgentRun.Status.WAITING_TASKS.name())
                                .and(TASK.STATUS.eq(Task.Status.RUNNING.name())))
                        .or(AGENT_RUN.STATUS.eq(AgentRun.Status.WAITING_TASKS.name())
                                .and(TASK.STATUS.eq(Task.Status.READY.name()))
                                .and(DSL.field("{0} ->> 'resumePlanId'", String.class,
                                        TASK.INPUT_JSON).isNotNull()))
                : AGENT_RUN.STATUS.in(AgentRun.Status.RUNNING.name(),
                        AgentRun.Status.WAITING_TASKS.name());
        Condition eligibility = agentTurn
                ? DSL.exists(DSL.selectOne()
                        .from(AGENT_RUN)
                        .where(AGENT_RUN.ID.eq(TASK.RUN_ID))
                        .and(runCondition))
                : TASK.ORIGIN.eq(TaskOrigin.USER_DIRECT.name())
                        .or(DSL.exists(DSL.selectOne()
                                .from(AGENT_RUN)
                                .where(AGENT_RUN.ID.eq(TASK.RUN_ID))
                                .and(runCondition)));
        Condition due = TASK.STATUS.eq(Task.Status.READY.name())
                        .and(TASK.NEXT_ACTION_AT.le(utc(now)))
                .or(TASK.STATUS.eq(Task.Status.RUNNING.name())
                        .and(TASK.LEASE_UNTIL.le(utc(now)))
                        .and(TASK.PROVIDER_REQUEST_ID.isNull()));
        var candidates = DSL.select(TASK.ID)
                .from(TASK)
                .where(TASK.CANCEL_REQUESTED.isFalse())
                .and(kindCondition)
                .and(bindingClause)
                .and(eligibility)
                .and(due)
                .orderBy(TASK.NEXT_ACTION_AT, TASK.CREATED_AT, TASK.ID)
                .limit(limit)
                .forUpdate().skipLocked()
                .asTable("c");
        return dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.RUNNING.name())
                .set(TASK.LEASE_OWNER, workerId)
                .set(TASK.LEASE_UNTIL, utc(leaseUntil))
                .set(TASK.LEASE_EPOCH, TASK.LEASE_EPOCH.plus(1))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .set(TASK.UPDATED_AT, utc(now))
                .from(candidates)
                .where(TASK.ID.eq(candidates.field(TASK.ID)))
                .returning(TASK.fields())
                .fetch(row -> mapTask(row.into(TASK)));
    }

    /** 仅未过期的当前 epoch 可续租；取消请求会使心跳失败。 */
    @Override
    public boolean heartbeat(
            UUID taskId,
            String workerId,
            long leaseEpoch,
            Instant now,
            Instant leaseUntil) {
        return dsl.update(TASK)
                .set(TASK.LEASE_UNTIL, utc(leaseUntil))
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(taskId))
                .and(TASK.STATUS.in(Task.Status.RUNNING.name(), Task.Status.SUBMITTING.name()))
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(leaseEpoch))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .and(TASK.CANCEL_REQUESTED.isFalse())
                .execute() == 1;
    }

    /** 仅当前未过期且未取消的 RUNNING 租约可写终态、输出和错误码。 */
    @Override
    public boolean finish(
            UUID taskId,
            String workerId,
            long leaseEpoch,
            Task.Status terminalStatus,
            JsonNode output,
            String errorCode,
            Instant now) {
        return dsl.update(TASK)
                .set(TASK.STATUS, terminalStatus.name())
                .set(TASK.OUTPUT_JSON, JSONB.valueOf(output == null ? "null" : output.toString()))
                .set(TASK.ERROR_CODE, errorCode)
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.COMPLETED_AT, utc(now))
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(taskId))
                .and(TASK.STATUS.eq(Task.Status.RUNNING.name()))
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(leaseEpoch))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .and(TASK.CANCEL_REQUESTED.isFalse())
                .execute() == 1;
    }

    /** 只阻断尚无外部请求 ID 的过期输入任务，避免把已提交任务当成本地失败。 */
    @Override
    public boolean blockStaleInput(UUID taskId, String workerId, long leaseEpoch,
            String errorCode, Instant now) {
        return dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.BLOCKED.name())
                .set(TASK.ERROR_CODE, errorCode)
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(taskId))
                .and(TASK.STATUS.eq(Task.Status.RUNNING.name()))
                .and(TASK.PROVIDER_REQUEST_ID.isNull())
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(leaseEpoch))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .and(TASK.CANCEL_REQUESTED.isFalse())
                .execute() == 1;
    }

    /** 仅所有前置成功且所需关键帧已选择时，将 PENDING 任务推进 READY。 */
    @Override
    public int promoteReady(UUID projectId, UUID runId, Instant now) {
        // 恢复判定依赖 input_json ->> 取值、(…)::uuid 转换、is distinct from 以及多别名
        // 关联子查询，这些 PG 专有表达式保留 SQL 文本；绑定值仍由 jOOQ 参数化。
        return dsl.execute("""
                        update task candidate
                        set status = 'READY', next_action_at = ?,
                            updated_at = ?, version = candidate.version + 1
                        where candidate.project_id = ?
                          and candidate.run_id = ?
                          and candidate.status = 'PENDING'
                          and candidate.cancel_requested = false
                          and exists (select 1 from agent_run r
                              where r.id = candidate.run_id
                                and r.status not in ('CANCEL_REQUESTED', 'CANCELED'))
                          and not exists (
                              select 1
                              from task_dependency dependency
                              join task predecessor
                                on predecessor.project_id = dependency.project_id
                               and predecessor.id = dependency.depends_on_task_id
                              where dependency.project_id = candidate.project_id
                                and dependency.task_id = candidate.id
                                and (predecessor.status <> 'SUCCEEDED'
                                  or predecessor.output_json ->> 'selected' = 'false')
                          )
                          and (candidate.kind <> 'AGENT_TURN'
                              or candidate.input_json ->> 'awaitKeyframes' is distinct from 'true'
                              or not exists (
                                  select 1 from task image_task
                                  left join shot_keyframe_selection choice
                                    on choice.project_id = image_task.project_id
                                   and choice.source_task_id = image_task.id
                                   and choice.shot_artifact_id =
                                       (image_task.input_json ->> 'shotArtifactId')::uuid
                                   and choice.shot_version_id =
                                       (image_task.input_json ->> 'shotVersionId')::uuid
                                   and choice.image_artifact_id =
                                       (image_task.output_json ->> 'artifactId')::uuid
                                   and choice.image_version_id =
                                       (image_task.output_json ->> 'artifactVersionId')::uuid
                                  where image_task.project_id = candidate.project_id
                                    and image_task.run_id = candidate.run_id
                                    and image_task.plan_id =
                                        (candidate.input_json ->> 'resumePlanId')::uuid
                                    and image_task.kind = 'IMAGE_GENERATION'
                                    and not exists (
                                        select 1 from task_manual_replacement replacement
                                        where replacement.original_task_id = image_task.id)
                                    and choice.source_task_id is null
                              ))
                        """, utc(now), utc(now), projectId, runId);
    }

    /** 取消 Run 的未终态任务；PENDING/READY 立即结束，其他任务只打取消标记。 */
    @Override
    public List<Task> requestCancellation(UUID projectId, UUID runId, Instant now) {
        List<UUID> canceledBeforeSubmission = dsl.update(TASK)
                .set(TASK.CANCEL_REQUESTED, true)
                .set(TASK.STATUS, DSL.when(TASK.STATUS.in(Task.Status.PENDING.name(),
                                        Task.Status.READY.name()),
                                Task.Status.CANCELED.name())
                        .otherwise(TASK.STATUS))
                .set(TASK.COMPLETED_AT, DSL.when(TASK.STATUS.in(Task.Status.PENDING.name(),
                                        Task.Status.READY.name()),
                                DSL.val(utc(now), TASK.COMPLETED_AT))
                        .otherwise(TASK.COMPLETED_AT))
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.RUN_ID.eq(runId))
                .and(TASK.STATUS.notIn(Task.Status.SUCCEEDED.name(), Task.Status.FAILED.name(),
                        Task.Status.CANCELED.name()))
                .returning(TASK.ID, TASK.KIND, TASK.STATUS)
                .fetch(record -> record.get(TASK.STATUS).equals(Task.Status.CANCELED.name())
                        && (record.get(TASK.KIND).equals(Task.Kind.IMAGE_GENERATION.name())
                                || record.get(TASK.KIND).equals(Task.Kind.VIDEO_GENERATION.name()))
                        ? record.get(TASK.ID) : null)
                .stream().filter(java.util.Objects::nonNull).toList();
        return canceledBeforeSubmission.stream()
                .map(id -> findById(id).orElseThrow())
                .toList();
    }

    /** 在网络调用前原子写入 SUBMITTING 和 Provider 尝试记录，作为崩溃核对依据。 */
    @Override
    public boolean beginSubmission(UUID taskId, String workerId, long leaseEpoch,
            UUID attemptId, UUID requestKey, String candidateOriginSha256, Instant now) {
        int changed = dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.SUBMITTING.name())
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(taskId))
                .and(TASK.STATUS.eq(Task.Status.RUNNING.name()))
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(leaseEpoch))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .and(TASK.CANCEL_REQUESTED.isFalse())
                .and(TASK.ORIGIN.eq(TaskOrigin.USER_DIRECT.name())
                        .or(DSL.exists(DSL.selectOne()
                                .from(AGENT_RUN)
                                .where(AGENT_RUN.ID.eq(TASK.RUN_ID))
                                .and(AGENT_RUN.STATUS.notIn(
                                        AgentRun.Status.CANCEL_REQUESTED.name(),
                                        AgentRun.Status.CANCELED.name())))))
                .execute();
        if (changed == 0) {
            return false;
        }
        dsl.insertInto(PROVIDER_ATTEMPT,
                        PROVIDER_ATTEMPT.ID, PROVIDER_ATTEMPT.PROJECT_ID,
                        PROVIDER_ATTEMPT.TASK_ID, PROVIDER_ATTEMPT.LEASE_EPOCH,
                        PROVIDER_ATTEMPT.STATUS, PROVIDER_ATTEMPT.REQUEST_KEY,
                        PROVIDER_ATTEMPT.CANDIDATE_REQUEST_ID,
                        PROVIDER_ATTEMPT.CANDIDATE_ORIGIN_SHA256,
                        PROVIDER_ATTEMPT.CONNECTION_ID, PROVIDER_ATTEMPT.CONNECTION_VERSION,
                        PROVIDER_ATTEMPT.CAPABILITY_ID, PROVIDER_ATTEMPT.CAPABILITY_VERSION,
                        PROVIDER_ATTEMPT.CREATED_AT, PROVIDER_ATTEMPT.UPDATED_AT)
                .select(DSL.select(DSL.val(attemptId),
                                TASK.PROJECT_ID,
                                TASK.ID,
                                DSL.val(leaseEpoch),
                                DSL.val(ProviderAttempt.Status.SUBMITTING.name()),
                                DSL.val(requestKey),
                                DSL.val(candidateOriginSha256 == null ? null : requestKey,
                                        UUID.class),
                                DSL.val(candidateOriginSha256, String.class),
                                TASK.CONNECTION_ID,
                                TASK.CONNECTION_VERSION,
                                TASK.CAPABILITY_ID,
                                TASK.CAPABILITY_VERSION,
                                DSL.val(utc(now), PROVIDER_ATTEMPT.CREATED_AT),
                                DSL.val(utc(now), PROVIDER_ATTEMPT.UPDATED_AT))
                        .from(TASK)
                        .where(TASK.ID.eq(taskId)))
                .execute();
        return true;
    }

    /** 保存 Provider 确认的原请求 ID 并释放提交租约；更新尝试账本必须恰好一行。 */
    @Override
    public boolean acknowledgeSubmission(UUID taskId, String workerId, long leaseEpoch,
            String providerRequestId, Instant nextActionAt, Instant now) {
        int changed = dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.WAITING_PROVIDER.name())
                .set(TASK.PROVIDER_REQUEST_ID, providerRequestId)
                .set(TASK.NEXT_ACTION_AT, utc(nextActionAt))
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(taskId))
                .and(TASK.STATUS.eq(Task.Status.SUBMITTING.name()))
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(leaseEpoch))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .execute();
        if (changed == 0) {
            return false;
        }
        int attemptChanged = dsl.update(PROVIDER_ATTEMPT)
                .set(PROVIDER_ATTEMPT.STATUS, ProviderAttempt.Status.ACCEPTED.name())
                .set(PROVIDER_ATTEMPT.PROVIDER_REQUEST_ID, providerRequestId)
                .set(PROVIDER_ATTEMPT.UPDATED_AT, utc(now))
                .where(PROVIDER_ATTEMPT.TASK_ID.eq(taskId))
                .and(PROVIDER_ATTEMPT.LEASE_EPOCH.eq(leaseEpoch))
                .and(PROVIDER_ATTEMPT.STATUS.eq(ProviderAttempt.Status.SUBMITTING.name()))
                .execute();
        if (attemptChanged != 1) {
            throw new IllegalStateException("Missing provider attempt for submission acknowledgement");
        }
        return true;
    }

    /** 轮询仍未完成时释放租约并安排再次查询，保留原 provider_request_id。 */
    @Override
    public boolean deferProviderPoll(UUID taskId, String workerId, long leaseEpoch,
            Instant nextActionAt, Instant now) {
        return dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.WAITING_PROVIDER.name())
                .set(TASK.NEXT_ACTION_AT, utc(nextActionAt))
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(taskId))
                .and(TASK.STATUS.eq(Task.Status.RUNNING.name()))
                .and(TASK.PROVIDER_REQUEST_ID.isNotNull())
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(leaseEpoch))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .execute() == 1;
    }

    /** 读取原 Provider 请求的连续查询失败次数；尚无账本行时返回零。 */
    @Override
    public int providerPollFailureCount(UUID taskId) {
        return dsl.select(TASK_PROVIDER_POLL_RETRY.FAILURE_COUNT)
                .from(TASK_PROVIDER_POLL_RETRY)
                .where(TASK_PROVIDER_POLL_RETRY.TASK_ID.eq(taskId))
                .fetchOptional(TASK_PROVIDER_POLL_RETRY.FAILURE_COUNT)
                .orElse(0);
    }

    /** 以任务为唯一键写入连续查询失败计数和最近稳定错误码。 */
    @Override
    public void recordProviderPollFailure(UUID taskId, int count, String errorCode, Instant now) {
        int changed = dsl.insertInto(TASK_PROVIDER_POLL_RETRY)
                .set(TASK_PROVIDER_POLL_RETRY.TASK_ID, taskId)
                .set(TASK_PROVIDER_POLL_RETRY.FAILURE_COUNT, count)
                .set(TASK_PROVIDER_POLL_RETRY.LAST_ERROR_CODE, errorCode)
                .set(TASK_PROVIDER_POLL_RETRY.UPDATED_AT, utc(now))
                .onConflict(TASK_PROVIDER_POLL_RETRY.TASK_ID)
                .doUpdate()
                .set(TASK_PROVIDER_POLL_RETRY.FAILURE_COUNT,
                        DSL.excluded(TASK_PROVIDER_POLL_RETRY.FAILURE_COUNT))
                .set(TASK_PROVIDER_POLL_RETRY.LAST_ERROR_CODE,
                        DSL.excluded(TASK_PROVIDER_POLL_RETRY.LAST_ERROR_CODE))
                .set(TASK_PROVIDER_POLL_RETRY.UPDATED_AT,
                        DSL.excluded(TASK_PROVIDER_POLL_RETRY.UPDATED_AT))
                .execute();
        if (changed != 1) throw new IllegalStateException("Provider poll retry ledger did not update");
    }

    /** 查询成功或归档完成后清除该请求的连续失败计数。 */
    @Override
    public void clearProviderPollFailures(UUID taskId) {
        dsl.deleteFrom(TASK_PROVIDER_POLL_RETRY)
                .where(TASK_PROVIDER_POLL_RETRY.TASK_ID.eq(taskId))
                .execute();
    }

    /** 原请求保留但配置不再安全时阻断轮询，并释放当前租约。 */
    @Override
    public boolean blockProviderPoll(UUID taskId, String workerId, long leaseEpoch,
            String errorCode, Instant now) {
        return dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.BLOCKED.name())
                .set(TASK.ERROR_CODE, errorCode)
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(taskId))
                .and(TASK.STATUS.eq(Task.Status.RUNNING.name()))
                .and(TASK.PROVIDER_REQUEST_ID.isNotNull())
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(leaseEpoch))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .execute() == 1;
    }

    /** 无锁、有界发现已过期的提交检查点；实际转换由后续条件更新完成。 */
    @Override
    public List<ExpiredSubmission> findExpiredSubmissions(Instant now, int limit) {
        return dsl.select(TASK.ID, TASK.PROJECT_ID, PROJECT.OWNER_ID)
                .from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(TASK.STATUS.eq(Task.Status.SUBMITTING.name()))
                .and(TASK.LEASE_UNTIL.le(utc(now)))
                .orderBy(TASK.LEASE_UNTIL, TASK.ID)
                .limit(limit)
                .fetch(record -> new ExpiredSubmission(
                        record.get(TASK.ID),
                        record.get(TASK.PROJECT_ID),
                        record.get(PROJECT.OWNER_ID)));
    }

    /** 仅当提交租约仍已过期且状态仍为 SUBMITTING 时转成 UNKNOWN。 */
    @Override
    public boolean recoverExpiredSubmission(UUID taskId, Instant now) {
        int changed = dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.UNKNOWN.name())
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.ERROR_CODE, ProviderFailureCodes.SUBMISSION_UNKNOWN)
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(taskId))
                .and(TASK.STATUS.eq(Task.Status.SUBMITTING.name()))
                .and(TASK.LEASE_UNTIL.le(utc(now)))
                .execute();
        if (changed == 0) {
            return false;
        }
        int attemptChanged = dsl.update(PROVIDER_ATTEMPT)
                .set(PROVIDER_ATTEMPT.STATUS, ProviderAttempt.Status.UNKNOWN.name())
                .set(PROVIDER_ATTEMPT.UPDATED_AT, utc(now))
                .where(PROVIDER_ATTEMPT.TASK_ID.eq(taskId))
                .and(PROVIDER_ATTEMPT.STATUS.eq(ProviderAttempt.Status.SUBMITTING.name()))
                .execute();
        if (attemptChanged != 1) {
            throw new IllegalStateException("Missing provider attempt for recovered submission");
        }
        return true;
    }

    /** 查找已请求取消且 RUNNING 租约过期的本地任务，不推断 Provider 已取消。 */
    @Override
    public List<ExpiredSubmission> findExpiredCanceledRunning(Instant now, int limit) {
        return dsl.select(TASK.ID, TASK.PROJECT_ID, PROJECT.OWNER_ID)
                .from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(TASK.STATUS.eq(Task.Status.RUNNING.name()))
                .and(TASK.CANCEL_REQUESTED.isTrue())
                .and(TASK.LEASE_UNTIL.le(utc(now)))
                .orderBy(TASK.LEASE_UNTIL, TASK.ID)
                .limit(limit)
                .fetch(record -> new ExpiredSubmission(
                        record.get(TASK.ID),
                        record.get(TASK.PROJECT_ID),
                        record.get(PROJECT.OWNER_ID)));
    }

    /** 仅关闭取消请求已持久化且租约仍过期的本地任务。 */
    @Override
    public boolean finishExpiredCanceledRunning(UUID taskId, Instant now) {
        return dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.CANCELED.name())
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.COMPLETED_AT, utc(now))
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(taskId))
                .and(TASK.STATUS.eq(Task.Status.RUNNING.name()))
                .and(TASK.CANCEL_REQUESTED.isTrue())
                .and(TASK.LEASE_UNTIL.le(utc(now)))
                .execute() == 1;
    }

    /** 将晚到输出按 taskId 与 epoch 唯一保存到旁路历史，不覆盖主任务输出。 */
    @Override
    public boolean recordLateResult(Task lease, JsonNode output, Instant now) {
        return dsl.insertInto(TASK_LATE_RESULT,
                        TASK_LATE_RESULT.ID, TASK_LATE_RESULT.PROJECT_ID,
                        TASK_LATE_RESULT.TASK_ID, TASK_LATE_RESULT.LEASE_EPOCH,
                        TASK_LATE_RESULT.OUTPUT_JSON, TASK_LATE_RESULT.CREATED_AT)
                .select(DSL.select(DSL.val(UUID.randomUUID(), TASK_LATE_RESULT.ID),
                                TASK.PROJECT_ID,
                                TASK.ID,
                                DSL.val(lease.leaseEpoch(), TASK_LATE_RESULT.LEASE_EPOCH),
                                DSL.val(JSONB.valueOf(output == null ? "null" : output.toString()),
                                        TASK_LATE_RESULT.OUTPUT_JSON),
                                DSL.val(utc(now), TASK_LATE_RESULT.CREATED_AT))
                        .from(TASK)
                        .where(TASK.ID.eq(lease.id()))
                        .and(TASK.LEASE_EPOCH.eq(lease.leaseEpoch()))
                        .and(TASK.CANCEL_REQUESTED.isTrue()))
                .onConflict(TASK_LATE_RESULT.TASK_ID, TASK_LATE_RESULT.LEASE_EPOCH)
                .doNothing()
                .execute() == 1;
    }

    /** 晚到结果归档后终结取消任务；接受仍持有原 epoch 的 Worker 或已释放的 UNKNOWN。 */
    @Override
    public boolean finishCanceled(Task lease, String workerId, Instant now) {
        return dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.CANCELED.name())
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.COMPLETED_AT, utc(now))
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(lease.id()))
                .and(TASK.LEASE_EPOCH.eq(lease.leaseEpoch()))
                .and(TASK.CANCEL_REQUESTED.isTrue())
                .and(TASK.STATUS.in(Task.Status.RUNNING.name(), Task.Status.SUBMITTING.name())
                        .and(TASK.LEASE_OWNER.eq(workerId))
                        .or(TASK.STATUS.eq(Task.Status.UNKNOWN.name())
                                .and(TASK.LEASE_OWNER.isNull())))
                .execute() == 1;
    }

    /** 同步生成结果须在提交租约仍有效时完成任务，并把对应 Provider 尝试记为已受理。 */
    @Override
    public boolean finishSubmitting(Task lease, String workerId, JsonNode output, Instant now) {
        int changed = dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.SUCCEEDED.name())
                .set(TASK.OUTPUT_JSON, JSONB.valueOf(output.toString()))
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.COMPLETED_AT, utc(now))
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(lease.id()))
                .and(TASK.STATUS.eq(Task.Status.SUBMITTING.name()))
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(lease.leaseEpoch()))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .and(TASK.CANCEL_REQUESTED.isFalse())
                .execute();
        if (changed == 0) {
            return false;
        }
        int attemptChanged = dsl.update(PROVIDER_ATTEMPT)
                .set(PROVIDER_ATTEMPT.STATUS, ProviderAttempt.Status.ACCEPTED.name())
                .set(PROVIDER_ATTEMPT.UPDATED_AT, utc(now))
                .where(PROVIDER_ATTEMPT.TASK_ID.eq(lease.id()))
                .and(PROVIDER_ATTEMPT.LEASE_EPOCH.eq(lease.leaseEpoch()))
                .and(PROVIDER_ATTEMPT.STATUS.eq(ProviderAttempt.Status.SUBMITTING.name()))
                .execute();
        if (attemptChanged != 1) {
            throw new IllegalStateException("Missing provider attempt for completed submission");
        }
        return true;
    }

    /** 只完成与当前任务保存的原 requestId 相同的轮询结果。 */
    @Override
    public boolean finishProviderResult(Task lease, String workerId, JsonNode output, Instant now) {
        return dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.SUCCEEDED.name())
                .set(TASK.OUTPUT_JSON, JSONB.valueOf(output.toString()))
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.COMPLETED_AT, utc(now))
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(lease.id()))
                .and(TASK.STATUS.eq(Task.Status.RUNNING.name()))
                .and(TASK.PROVIDER_REQUEST_ID.eq(lease.providerRequestId()))
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(lease.leaseEpoch()))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .and(TASK.CANCEL_REQUESTED.isFalse())
                .execute() == 1;
    }

    /** Provider 明确拒绝时终结任务并标记尝试 REJECTED；超时不得调用此转换。 */
    @Override
    public boolean rejectSubmission(Task lease, String workerId, String errorCode, Instant now) {
        int changed = dsl.update(TASK)
                .set(TASK.STATUS, DSL.when(TASK.CANCEL_REQUESTED,
                                Task.Status.CANCELED.name())
                        .otherwise(Task.Status.FAILED.name()))
                .set(TASK.ERROR_CODE, errorCode)
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.COMPLETED_AT, utc(now))
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(lease.id()))
                .and(TASK.KIND.in(Task.Kind.IMAGE_GENERATION.name(),
                        Task.Kind.VIDEO_GENERATION.name()))
                .and(TASK.STATUS.eq(Task.Status.SUBMITTING.name()))
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(lease.leaseEpoch()))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .execute();
        if (changed == 0) {
            return false;
        }
        int attemptChanged = dsl.update(PROVIDER_ATTEMPT)
                .set(PROVIDER_ATTEMPT.STATUS, ProviderAttempt.Status.REJECTED.name())
                .set(PROVIDER_ATTEMPT.UPDATED_AT, utc(now))
                .where(PROVIDER_ATTEMPT.TASK_ID.eq(lease.id()))
                .and(PROVIDER_ATTEMPT.LEASE_EPOCH.eq(lease.leaseEpoch()))
                .and(PROVIDER_ATTEMPT.STATUS.eq(ProviderAttempt.Status.SUBMITTING.name()))
                .execute();
        if (attemptChanged != 1) {
            throw new IllegalStateException("Missing provider attempt for confirmed rejection");
        }
        return true;
    }

    /**
     * 无法确认外部是否受理或完成时立即写入 UNKNOWN。
     *
     * <p>与 {@link #rejectSubmission} 的三处有意差异，都在约束与既有语义上必需：
     * <ul>
     *   <li>{@code completed_at} 必须保持为空：{@code ck_task_completion} 只允许
     *       SUCCEEDED/FAILED/CANCELED 有完成时间，UNKNOWN 不是终态；</li>
     *   <li>不看 {@code cancel_requested}：{@code recoverExpiredSubmission} 已把 UNKNOWN
     *       定义为无条件结果，否则同一种响应丢失会因用户点没点过取消而产生两种状态；</li>
     *   <li>{@code lease_owner} 与 {@code lease_until} 必须同时清空，满足 {@code ck_task_lease_pair}。</li>
     * </ul>
     * 租约条件要求仍然有效，因此与只处理过期租约的兜底扫描互斥，不会同时写同一行尝试。
     */
    @Override
    public boolean markSubmissionUnknown(Task lease, String workerId, String errorCode,
            Instant now) {
        int changed = dsl.update(TASK)
                .set(TASK.STATUS, Task.Status.UNKNOWN.name())
                .set(TASK.ERROR_CODE, errorCode)
                .set(TASK.LEASE_OWNER, (String) null)
                .set(TASK.LEASE_UNTIL, (OffsetDateTime) null)
                .set(TASK.UPDATED_AT, utc(now))
                .set(TASK.VERSION, TASK.VERSION.plus(1))
                .where(TASK.ID.eq(lease.id()))
                .and(TASK.KIND.in(Task.Kind.IMAGE_GENERATION.name(),
                        Task.Kind.VIDEO_GENERATION.name()))
                .and(TASK.STATUS.eq(Task.Status.SUBMITTING.name()))
                .and(TASK.LEASE_OWNER.eq(workerId))
                .and(TASK.LEASE_EPOCH.eq(lease.leaseEpoch()))
                .and(TASK.LEASE_UNTIL.gt(utc(now)))
                .execute();
        if (changed == 0) {
            return false;
        }
        int attemptChanged = dsl.update(PROVIDER_ATTEMPT)
                .set(PROVIDER_ATTEMPT.STATUS, ProviderAttempt.Status.UNKNOWN.name())
                .set(PROVIDER_ATTEMPT.UPDATED_AT, utc(now))
                .where(PROVIDER_ATTEMPT.TASK_ID.eq(lease.id()))
                .and(PROVIDER_ATTEMPT.LEASE_EPOCH.eq(lease.leaseEpoch()))
                .and(PROVIDER_ATTEMPT.STATUS.eq(ProviderAttempt.Status.SUBMITTING.name()))
                .execute();
        if (attemptChanged != 1) {
            throw new IllegalStateException("Missing provider attempt for uncertain submission");
        }
        return true;
    }

    /** 统一把任务行映射为领域对象，JSONB 列取文本后再交给 Jackson。 */
    private Task mapTask(TaskRecord row) {
        return new Task(
                row.getId(),
                row.getProjectId(),
                row.getRunId(),
                row.getPlanId(),
                row.getStepKey(),
                Task.Kind.valueOf(row.getKind()),
                Task.Status.valueOf(row.getStatus()),
                row.getCancelRequested(),
                objectMapper.readTree(row.getInputJson().data()),
                row.getInputHash(),
                row.getOutputJson() == null
                        ? null
                        : objectMapper.readTree(row.getOutputJson().data()),
                row.getProviderId(),
                row.getProviderRequestId(),
                row.getAttemptNo(),
                row.getNextActionAt().toInstant(),
                row.getLeaseOwner(),
                row.getLeaseUntil() == null ? null : row.getLeaseUntil().toInstant(),
                row.getLeaseEpoch(),
                row.getVersion(),
                row.getErrorCode(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant(),
                row.getCompletedAt() == null ? null : row.getCompletedAt().toInstant());
    }

    /** 将业务 Instant 显式转换为 PostgreSQL JDBC UTC 偏移时间。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
