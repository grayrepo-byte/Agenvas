package dev.agenvas.task.infrastructure;

import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.domain.Task;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.task.domain.ProviderAttempt;
import dev.agenvas.run.application.RunTaskCancellation;
import dev.agenvas.run.application.RunTaskCreation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 任务队列实现；使用短事务 SKIP LOCKED 认领和 lease_epoch 隔离旧 Worker。 */
@Repository
public class JdbcTaskRepository implements TaskRepository, RunTaskCancellation, RunTaskCreation {

    /** 执行参数化 SQL 和条件更新。 */
    private final JdbcClient jdbcClient;
    /** 将 JSONB 输入、输出映射为受控 JSON 树。 */
    private final ObjectMapper objectMapper;
    /** 统一读取任务状态、外部请求 ID、租约 epoch 和时间字段。 */
    private final RowMapper<Task> taskMapper;

    /** 预编译任务行映射，所有时间按数据库 UTC 时间戳转为 Instant。 */
    public JdbcTaskRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.taskMapper = (resultSet, rowNumber) -> new Task(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getObject("plan_id", UUID.class),
                resultSet.getString("step_key"),
                Task.Kind.valueOf(resultSet.getString("kind")),
                Task.Status.valueOf(resultSet.getString("status")),
                resultSet.getBoolean("cancel_requested"),
                objectMapper.readTree(resultSet.getString("input_json")),
                resultSet.getString("input_hash"),
                resultSet.getString("output_json") == null
                        ? null
                        : objectMapper.readTree(resultSet.getString("output_json")),
                resultSet.getObject("provider_id", UUID.class),
                resultSet.getString("provider_request_id"),
                resultSet.getInt("attempt_no"),
                resultSet.getObject("next_action_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("lease_owner"),
                Optional.ofNullable(resultSet.getObject("lease_until", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant)
                        .orElse(null),
                resultSet.getLong("lease_epoch"),
                resultSet.getLong("version"),
                resultSet.getString("error_code"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(resultSet.getObject("completed_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant)
                        .orElse(null));
    }

    @Override
    public void bindMediaTask(UUID taskId, MediaCapabilityBinding binding) {
        int changed = jdbcClient.sql("update task set capability_id=:capabilityId,"
                + "capability_version=:capabilityVersion,connection_id=:connectionId,"
                + "connection_version=:connectionVersion where id=:taskId "
                + "and kind in ('IMAGE_GENERATION','VIDEO_GENERATION') "
                + "and capability_id is null and status in ('PENDING','READY')")
                .param("capabilityId", binding.capabilityId())
                .param("capabilityVersion", binding.capabilityVersion())
                .param("connectionId", binding.connectionId())
                .param("connectionVersion", binding.connectionVersion())
                .param("taskId", taskId).update();
        if (changed != 1) {
            throw new IllegalStateException("New media Task binding was not saved once");
        }
    }

    /** 所有者和项目联合授权后读取最多 100 条外部提交尝试，不读取文件或凭证。 */
    @Override
    public List<ProviderAttempt> listProviderAttempts(UUID ownerId, UUID projectId, UUID taskId) {
        return jdbcClient.sql("""
                        select pa.id, pa.task_id, pa.status, pa.request_key,
                               pa.candidate_request_id, pa.candidate_origin_sha256,
                               pa.provider_request_id, pa.created_at, pa.updated_at
                        from provider_attempt pa
                        join task t on t.id = pa.task_id and t.project_id = pa.project_id
                        join project p on p.id = t.project_id
                        where p.owner_id = :ownerId and t.project_id = :projectId
                          and t.id = :taskId
                        order by pa.created_at desc, pa.id desc
                        limit 100
                        """)
                .param("ownerId", ownerId)
                .param("projectId", projectId)
                .param("taskId", taskId)
                .query((rs, row) -> new ProviderAttempt(
                        rs.getObject("id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        ProviderAttempt.Status.valueOf(rs.getString("status")),
                        rs.getObject("request_key", UUID.class),
                        rs.getObject("candidate_request_id", UUID.class),
                        rs.getString("candidate_origin_sha256"),
                        rs.getString("provider_request_id"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** 查询一个 UNKNOWN 原任务已建立的人工替代关系。 */
    @Override
    public Optional<ManualReplacement> findManualReplacement(UUID projectId, UUID originalTaskId) {
        return jdbcClient.sql("""
                        select project_id, original_task_id, replacement_task_id,
                               approved_by_user_id, original_task_version,
                               idempotency_key, created_at
                        from task_manual_replacement
                        where project_id = :projectId and original_task_id = :originalTaskId
                        """)
                .param("projectId", projectId).param("originalTaskId", originalTaskId)
                .query(this::manualReplacement).optional();
    }

    /** 按用户、项目和幂等键查找人工重试记录，供同命令安全重放。 */
    @Override
    public Optional<ManualReplacement> findManualReplacementByKey(UUID projectId, UUID ownerId,
            String idempotencyKey) {
        return jdbcClient.sql("""
                        select project_id, original_task_id, replacement_task_id,
                               approved_by_user_id, original_task_version,
                               idempotency_key, created_at
                        from task_manual_replacement
                        where project_id = :projectId and approved_by_user_id = :ownerId
                          and idempotency_key = :idempotencyKey
                        """)
                .param("projectId", projectId).param("ownerId", ownerId)
                .param("idempotencyKey", idempotencyKey)
                .query(this::manualReplacement).optional();
    }

    /** 从审计行恢复原任务、替代任务、批准用户及确认时的原版本。 */
    private ManualReplacement manualReplacement(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ManualReplacement(rs.getObject("project_id", UUID.class),
                rs.getObject("original_task_id", UUID.class),
                rs.getObject("replacement_task_id", UUID.class),
                rs.getObject("approved_by_user_id", UUID.class),
                rs.getLong("original_task_version"), rs.getString("idempotency_key"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    /** 保存用户承担重复费用风险的审计链接；唯一约束限制一项原任务只能替代一次。 */
    @Override
    public void createManualReplacement(ManualReplacement replacement) {
        int changed = jdbcClient.sql("""
                        insert into task_manual_replacement (original_task_id, project_id,
                            replacement_task_id, approved_by_user_id, original_task_version,
                            idempotency_key, confirmation_code, created_at)
                        values (:originalTaskId, :projectId, :replacementTaskId, :ownerId,
                            :originalVersion, :idempotencyKey,
                            'ACCEPT_POSSIBLE_DUPLICATE_COST', :now)
                        """)
                .param("originalTaskId", replacement.originalTaskId())
                .param("projectId", replacement.projectId())
                .param("replacementTaskId", replacement.replacementTaskId())
                .param("ownerId", replacement.approvedByUserId())
                .param("originalVersion", replacement.originalTaskVersion())
                .param("idempotencyKey", replacement.idempotencyKey())
                .param("now", utc(replacement.createdAt())).update();
        if (changed != 1) throw new IllegalStateException("Manual replacement was not inserted");
    }

    /** 按稳定 UUID 顺序返回任务的全部前置依赖。 */
    @Override
    public List<UUID> dependencyIds(UUID projectId, UUID taskId) {
        return jdbcClient.sql("""
                        select depends_on_task_id from task_dependency
                        where project_id = :projectId and task_id = :taskId
                        order by depends_on_task_id
                        """)
                .param("projectId", projectId).param("taskId", taskId)
                .query(UUID.class).list();
    }

    /** 查询依赖指定任务的所有消费者，供人工替代前确认其均未启动。 */
    @Override
    public List<Task> dependentTasks(UUID projectId, UUID taskId) {
        return jdbcClient.sql(selectProjection() + """
                        join task_dependency d on d.project_id = t.project_id
                          and d.task_id = t.id
                        where d.project_id = :projectId and d.depends_on_task_id = :taskId
                        order by t.created_at, t.id
                        """)
                .param("projectId", projectId).param("taskId", taskId)
                .query(taskMapper).list();
    }

    /** 只把仍为 PENDING 的消费者改为依赖替代任务，并递增受影响版本。 */
    @Override
    public List<Task> rewirePendingDependents(UUID projectId, UUID originalTaskId,
            UUID replacementTaskId, Instant now) {
        int changed = jdbcClient.sql("""
                        update task_dependency d set depends_on_task_id = :replacementTaskId
                        where d.project_id = :projectId
                          and d.depends_on_task_id = :originalTaskId
                          and exists (select 1 from task t where t.id = d.task_id
                              and t.project_id = d.project_id and t.status = 'PENDING')
                        """)
                .param("projectId", projectId).param("originalTaskId", originalTaskId)
                .param("replacementTaskId", replacementTaskId).update();
        if (changed == 0) return List.of();
        int versioned = jdbcClient.sql("""
                        update task t set version = version + 1, updated_at = :now
                        where t.project_id = :projectId and t.status = 'PENDING'
                          and exists (select 1 from task_dependency d
                              where d.project_id = t.project_id and d.task_id = t.id
                                and d.depends_on_task_id = :replacementTaskId)
                        """)
                .param("projectId", projectId).param("replacementTaskId", replacementTaskId)
                .param("now", utc(now)).update();
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
        jdbcClient.sql("""
                        insert into task (
                            id, project_id, run_id, plan_id, step_key, kind, status,
                            input_json, input_hash, output_json, provider_id, provider_request_id,
                            attempt_no, next_action_at, lease_owner, lease_until, lease_epoch,
                            version, error_code, created_at, updated_at, completed_at
                        ) values (
                            :id, :projectId, :runId, :planId, :stepKey, :kind, :status,
                            cast(:inputJson as jsonb), :inputHash, null, :providerId, null,
                            :attemptNo, :nextActionAt, null, null, :leaseEpoch,
                            :version, null, :createdAt, :updatedAt, null
                        )
                        """)
                .param("id", task.id())
                .param("projectId", task.projectId())
                .param("runId", task.runId(), java.sql.Types.OTHER)
                .param("planId", task.planId(), java.sql.Types.OTHER)
                .param("stepKey", task.stepKey())
                .param("kind", task.kind().name())
                .param("status", task.status().name())
                .param("inputJson", task.input().toString())
                .param("inputHash", task.inputHash())
                .param("providerId", task.providerId(), java.sql.Types.OTHER)
                .param("attemptNo", task.attemptNo())
                .param("nextActionAt", utc(task.nextActionAt()))
                .param("leaseEpoch", task.leaseEpoch())
                .param("version", task.version())
                .param("createdAt", utc(task.createdAt()))
                .param("updatedAt", utc(task.updatedAt()))
                .update();
        for (UUID dependencyId : dependencyIds) {
            jdbcClient.sql("""
                            insert into task_dependency (
                                project_id, task_id, depends_on_task_id, required_output_key
                            ) values (:projectId, :taskId, :dependencyId, null)
                            """)
                    .param("projectId", task.projectId())
                    .param("taskId", task.id())
                    .param("dependencyId", dependencyId)
                    .update();
        }
    }

    /** 固定任务创建时的既有产物目标或新输出槽位，结果归档时使用该快照做 CAS。 */
    @Override
    public void createArtifactTarget(ArtifactTarget target) {
        int changed = jdbcClient.sql("""
                        insert into task_artifact_target (task_id, project_id, artifact_id,
                            expected_current_version_id, expected_artifact_version,
                            output_slot_key)
                        values (:taskId, :projectId, :artifactId, :expectedVersionId,
                            :expectedArtifactVersion, :outputSlotKey)
                        """)
                .param("taskId", target.taskId())
                .param("projectId", target.projectId())
                .param("artifactId", target.artifactId())
                .param("expectedVersionId", target.expectedCurrentVersionId())
                .param("expectedArtifactVersion", target.expectedArtifactVersion())
                .param("outputSlotKey", target.outputSlotKey())
                .update();
        if (changed != 1) {
            throw new IllegalStateException("Failed to bind Task Artifact target");
        }
    }

    /** 读取任务输出目标的固定产物版本或输出槽位。 */
    @Override
    public Optional<ArtifactTarget> findArtifactTarget(UUID taskId) {
        return jdbcClient.sql("""
                        select task_id, project_id, artifact_id, expected_current_version_id,
                               expected_artifact_version, output_slot_key
                        from task_artifact_target where task_id = :taskId
                        """)
                .param("taskId", taskId)
                .query((rs, row) -> new ArtifactTarget(
                        rs.getObject("task_id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("artifact_id", UUID.class),
                        rs.getObject("expected_current_version_id", UUID.class),
                        rs.getLong("expected_artifact_version"),
                        rs.getString("output_slot_key")))
                .optional();
    }

    /** 按所有者、项目和任务 ID 读取，避免暴露其他项目资源。 */
    @Override
    public Optional<Task> find(UUID ownerId, UUID projectId, UUID taskId) {
        return jdbcClient.sql(selectProjection() + """
                        join project p on p.id = t.project_id
                        where t.id = :taskId and t.project_id = :projectId
                          and p.owner_id = :ownerId
                        """)
                .param("taskId", taskId)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .query(taskMapper)
                .optional();
    }

    /** 内部状态机按 ID 读取任务；该方法本身不执行 API 权限检查。 */
    @Override
    public Optional<Task> findById(UUID taskId) {
        return jdbcClient.sql(selectProjection() + "where t.id = :taskId")
                .param("taskId", taskId)
                .query(taskMapper)
                .optional();
    }

    /** 按项目命令键定位导出任务，用于校验幂等请求载荷。 */
    @Override
    public Optional<Task> findExportByStepKey(UUID ownerId, UUID projectId, String stepKey) {
        return jdbcClient.sql(selectProjection() + """
                        join project p on p.id = t.project_id
                        where t.project_id = :projectId and p.owner_id = :ownerId
                          and t.kind = 'MEDIA_EXPORT' and t.step_key = :stepKey
                        """)
                .param("projectId", projectId).param("ownerId", ownerId)
                .param("stepKey", stepKey).query(taskMapper).optional();
    }

    /** 返回最近 100 条项目导出，即使创建它们的 Run 已结束。 */
    @Override
    public List<Task> listExports(UUID ownerId, UUID projectId) {
        return jdbcClient.sql(selectProjection() + """
                        join project p on p.id = t.project_id
                        where t.project_id = :projectId and p.owner_id = :ownerId
                          and t.kind = 'MEDIA_EXPORT'
                        order by t.created_at desc, t.id desc limit 100
                        """)
                .param("projectId", projectId).param("ownerId", ownerId)
                .query(taskMapper).list();
    }

    /** READY 导出立即转 CANCELED；RUNNING 导出只记录取消请求供 Worker 停止。 */
    @Override
    public boolean requestExportCancellation(UUID projectId, UUID taskId, Instant now) {
        return jdbcClient.sql("""
                        update task set cancel_requested = true,
                            status = case when status = 'READY' then 'CANCELED' else status end,
                            completed_at = case when status = 'READY' then :now else completed_at end,
                            updated_at = :now, version = version + 1
                        where id = :taskId and project_id = :projectId
                          and kind = 'MEDIA_EXPORT' and status in ('READY', 'RUNNING')
                          and cancel_requested = false
                        """)
                .param("taskId", taskId).param("projectId", projectId)
                .param("now", utc(now)).update() == 1;
    }

    /** 从数据库反查任务项目的权威所有者，不能使用模型输入中的 ownerId。 */
    @Override
    public Optional<UUID> ownerId(UUID taskId) {
        return jdbcClient.sql("""
                        select p.owner_id from task t
                        join project p on p.id = t.project_id where t.id = :taskId
                        """)
                .param("taskId", taskId)
                .query(UUID.class)
                .optional();
    }

    /** 按创建时间和 ID 稳定排序读取指定所有者 Run 的任务。 */
    @Override
    public List<Task> listByRun(UUID ownerId, UUID projectId, UUID runId) {
        return jdbcClient.sql(selectProjection() + """
                        join project p on p.id = t.project_id
                        where t.project_id = :projectId and t.run_id = :runId
                          and p.owner_id = :ownerId
                        order by t.created_at, t.id
                        """)
                .param("projectId", projectId)
                .param("runId", runId)
                .param("ownerId", ownerId)
                .query(taskMapper)
                .list();
    }

    /** 返回项目内最近 100 个 UNKNOWN 任务，不依赖活动 Run 槽位。 */
    @Override
    public List<Task> listUnknown(UUID ownerId, UUID projectId) {
        return jdbcClient.sql(selectProjection() + """
                        join project p on p.id = t.project_id
                        where t.project_id = :projectId and p.owner_id = :ownerId
                          and t.status = 'UNKNOWN'
                        order by t.updated_at desc, t.id limit 100
                        """)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .query(taskMapper)
                .list();
    }

    /** 统计指定 Run 的任务类别数量，调用方须已锁定 Run 以防预算并发超额。 */
    @Override
    public long countByRunAndKind(UUID projectId, UUID runId, Task.Kind kind) {
        return jdbcClient.sql("""
                        select count(*) from task where project_id = :projectId
                          and run_id = :runId and kind = :kind
                        """)
                .param("projectId", projectId).param("runId", runId)
                .param("kind", kind.name()).query(Long.class).single();
    }

    /** 认领任意到期非 Agent 任务；Agent 回合留给独立调度器。 */
    @Override
    public List<Task> claimDue(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil, null);
    }

    /** 仅认领 Mock 图片适配器可处理的到期图片任务。 */
    @Override
    public List<Task> claimDueImages(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil, Task.Kind.IMAGE_GENERATION);
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

    /** 锁定全局派发门禁；任一 ComfyUI 请求仍活动或状态未知时不提交新请求。 */
    private List<Task> claimDueComfy(String workerId, Instant now, Instant leaseUntil,
            Task.Kind kind) {
        jdbcClient.sql("select id from provider_dispatch_gate where id = 1 for update")
                .query(Integer.class).single();
        long occupied = jdbcClient.sql("""
                        select count(*) from task
                        where kind in ('IMAGE_GENERATION', 'VIDEO_GENERATION')
                          and (status in ('SUBMITTING', 'WAITING_PROVIDER')
                            or (status = 'UNKNOWN' and not exists (
                                select 1 from task_manual_replacement replacement
                                where replacement.original_task_id = task.id))
                            or (status = 'BLOCKED' and provider_request_id is not null)
                            or (status = 'RUNNING'
                                and (provider_request_id is not null or lease_until > :now)))
                        """)
                .param("now", utc(now)).query(Long.class).single();
        return occupied == 0
                ? claimDueKind(workerId, 1, now, leaseUntil, kind)
                : List.of();
    }

    /** 只认领到期视频任务，避免 Mock 图片路径误消费视频。 */
    @Override
    public List<Task> claimDueVideos(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil, Task.Kind.VIDEO_GENERATION);
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
                Task.Kind.IMAGE_GENERATION);
    }

    /** 限定 ComfyUI 视频轮询器只接管视频任务。 */
    @Override
    public List<Task> claimDueComfyVideoPolls(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimProviderPolls(workerId, limit, now, leaseUntil,
                Task.Kind.VIDEO_GENERATION);
    }

    /** 只按既有请求 ID 选取等待或租约过期的任务，并递增 fencing epoch。 */
    private List<Task> claimProviderPolls(String workerId, int limit, Instant now,
            Instant leaseUntil, Task.Kind onlyKind) {
        String kindClause = onlyKind == null
                ? " kind in ('IMAGE_GENERATION', 'VIDEO_GENERATION') "
                : " kind = '" + onlyKind.name() + "' ";
        return jdbcClient.sql("""
                        with candidates as (
                            select id from task
                            where """ + kindClause + """
                              and provider_request_id is not null
                              and ((status = 'WAITING_PROVIDER' and next_action_at <= :now)
                                or (status = 'RUNNING' and lease_until <= :now))
                            order by next_action_at, created_at, id
                            for update skip locked limit :limit
                        )
                        update task t set status = 'RUNNING', lease_owner = :workerId,
                            lease_until = :leaseUntil, lease_epoch = t.lease_epoch + 1,
                            version = t.version + 1, updated_at = :now
                        from candidates c where t.id = c.id
                        returning t.id, t.project_id, t.run_id, t.plan_id, t.step_key,
                            t.kind, t.status, t.cancel_requested,
                            t.input_json::text as input_json, t.input_hash,
                            t.output_json::text as output_json, t.provider_id,
                            t.provider_request_id, t.attempt_no, t.next_action_at,
                            t.lease_owner, t.lease_until, t.lease_epoch, t.version,
                            t.error_code, t.created_at, t.updated_at, t.completed_at
                        """)
                .param("now", utc(now)).param("limit", limit)
                .param("workerId", workerId).param("leaseUntil", utc(leaseUntil))
                .query(taskMapper).list();
    }

    /** 只认领未取消、项目仍活动且 Run 为空的项目级导出任务。 */
    @Override
    public List<Task> claimDueExports(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return jdbcClient.sql("""
                        with candidates as (
                            select t.id from task t
                            join project p on p.id = t.project_id
                            where t.kind = 'MEDIA_EXPORT' and t.run_id is null
                              and p.status = 'ACTIVE' and t.cancel_requested = false
                              and ((t.status = 'READY' and t.next_action_at <= :now)
                                or (t.status = 'RUNNING' and t.lease_until <= :now))
                            order by t.next_action_at, t.created_at, t.id
                            for update of t skip locked limit :limit
                        )
                        update task t set status = 'RUNNING', lease_owner = :workerId,
                            lease_until = :leaseUntil, lease_epoch = t.lease_epoch + 1,
                            version = t.version + 1, updated_at = :now
                        from candidates c where t.id = c.id
                        returning t.id, t.project_id, t.run_id, t.plan_id, t.step_key,
                            t.kind, t.status, t.cancel_requested,
                            t.input_json::text as input_json, t.input_hash,
                            t.output_json::text as output_json, t.provider_id,
                            t.provider_request_id, t.attempt_no, t.next_action_at,
                            t.lease_owner, t.lease_until, t.lease_epoch, t.version,
                            t.error_code, t.created_at, t.updated_at, t.completed_at
                        """)
                .param("now", utc(now)).param("limit", limit)
                .param("workerId", workerId).param("leaseUntil", utc(leaseUntil))
                .query(taskMapper).list();
    }

    /** 单独认领 Agent 回合，Run 状态和恢复计划条件在 SQL 中再次限定。 */
    @Override
    public List<Task> claimDueAgentTurns(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil, Task.Kind.AGENT_TURN);
    }

    /** 在业务事务内锁住任务行，核验项目、Run、Worker、epoch、取消和租约期限。 */
    @Override
    public boolean lockActiveAgentTurnLease(UUID projectId, UUID runId, UUID taskId,
            String workerId, long leaseEpoch, Instant now) {
        return jdbcClient.sql("""
                        select id from task
                        where id = :taskId and project_id = :projectId and run_id = :runId
                          and kind = 'AGENT_TURN' and status = 'RUNNING'
                          and cancel_requested = false and lease_owner = :workerId
                          and lease_epoch = :leaseEpoch and lease_until > :now
                        for update
                        """)
                .param("taskId", taskId)
                .param("projectId", projectId)
                .param("runId", runId)
                .param("workerId", workerId)
                .param("leaseEpoch", leaseEpoch)
                .param("now", utc(now))
                .query(UUID.class).optional().isPresent();
    }

    /** 模型与媒体使用互斥认领域；认领时递增 epoch 并返回更新后完整任务行。 */
    private List<Task> claimDueKind(String workerId, int limit, Instant now,
            Instant leaseUntil, Task.Kind onlyKind) {
        boolean agentTurn = onlyKind == Task.Kind.AGENT_TURN;
        String kindClause = onlyKind == null ? " and kind <> 'AGENT_TURN' "
                : " and kind = '" + onlyKind.name() + "' ";
        String runClause = agentTurn
                ? " and (r.status in ('QUEUED', 'RUNNING') "
                        + "or (r.status in ('WAITING_APPROVAL', 'WAITING_TASKS') "
                        + "and task.status = 'RUNNING') "
                        + "or (r.status = 'WAITING_TASKS' and task.status = 'READY' "
                        + "and task.input_json ->> 'resumePlanId' is not null)) "
                : " and r.status in ('RUNNING', 'WAITING_TASKS') ";
        return jdbcClient.sql("""
                        with candidates as (
                            select id
                            from task
                            where cancel_requested = false
                            """ + kindClause + """
                              and exists (select 1 from agent_run r
                                  where r.id = task.run_id
                            """ + runClause + """
                                  )
                              and ((status = 'READY' and next_action_at <= :now)
                               or (status = 'RUNNING' and lease_until <= :now
                                   and provider_request_id is null))
                            order by next_action_at, created_at, id
                            for update skip locked
                            limit :limit
                        )
                        update task t
                        set status = 'RUNNING',
                            lease_owner = :workerId,
                            lease_until = :leaseUntil,
                            lease_epoch = t.lease_epoch + 1,
                            version = t.version + 1,
                            updated_at = :now
                        from candidates c
                        where t.id = c.id
                        returning t.id, t.project_id, t.run_id, t.plan_id, t.step_key,
                                  t.kind, t.status, t.cancel_requested,
                                  t.input_json::text as input_json,
                                  t.input_hash, t.output_json::text as output_json,
                                  t.provider_id, t.provider_request_id, t.attempt_no,
                                  t.next_action_at, t.lease_owner, t.lease_until,
                                  t.lease_epoch, t.version, t.error_code,
                                  t.created_at, t.updated_at, t.completed_at
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .param("workerId", workerId)
                .param("leaseUntil", utc(leaseUntil))
                .query(taskMapper)
                .list();
    }

    /** 仅未过期的当前 epoch 可续租；取消请求会使心跳失败。 */
    @Override
    public boolean heartbeat(
            UUID taskId,
            String workerId,
            long leaseEpoch,
            Instant now,
            Instant leaseUntil) {
        return jdbcClient.sql("""
                        update task
                        set lease_until = :leaseUntil, updated_at = :now, version = version + 1
                        where id = :taskId and status in ('RUNNING', 'SUBMITTING')
                          and lease_owner = :workerId and lease_epoch = :leaseEpoch
                          and lease_until > :now and cancel_requested = false
                        """)
                .param("leaseUntil", utc(leaseUntil))
                .param("now", utc(now))
                .param("taskId", taskId)
                .param("workerId", workerId)
                .param("leaseEpoch", leaseEpoch)
                .update() == 1;
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
        return jdbcClient.sql("""
                        update task
                        set status = :status,
                            output_json = cast(:outputJson as jsonb),
                            error_code = :errorCode,
                            lease_owner = null,
                            lease_until = null,
                            completed_at = :now,
                            updated_at = :now,
                            version = version + 1
                        where id = :taskId and status = 'RUNNING'
                          and lease_owner = :workerId and lease_epoch = :leaseEpoch
                          and lease_until > :now and cancel_requested = false
                        """)
                .param("status", terminalStatus.name())
                .param("outputJson", output == null ? "null" : output.toString())
                .param("errorCode", errorCode, java.sql.Types.VARCHAR)
                .param("now", utc(now))
                .param("taskId", taskId)
                .param("workerId", workerId)
                .param("leaseEpoch", leaseEpoch)
                .update() == 1;
    }

    /** 只阻断尚无外部请求 ID 的过期输入任务，避免把已提交任务当成本地失败。 */
    @Override
    public boolean blockStaleInput(UUID taskId, String workerId, long leaseEpoch,
            String errorCode, Instant now) {
        return jdbcClient.sql("""
                        update task set status = 'BLOCKED', error_code = :errorCode,
                            lease_owner = null, lease_until = null,
                            updated_at = :now, version = version + 1
                        where id = :taskId and status = 'RUNNING'
                          and provider_request_id is null
                          and lease_owner = :workerId and lease_epoch = :leaseEpoch
                          and lease_until > :now and cancel_requested = false
                        """)
                .param("taskId", taskId).param("workerId", workerId)
                .param("leaseEpoch", leaseEpoch).param("errorCode", errorCode)
                .param("now", utc(now)).update() == 1;
    }

    /** 仅所有前置成功且所需关键帧已选择时，将 PENDING 任务推进 READY。 */
    @Override
    public int promoteReady(UUID projectId, UUID runId, Instant now) {
        return jdbcClient.sql("""
                        update task candidate
                        set status = 'READY', next_action_at = :now,
                            updated_at = :now, version = candidate.version + 1
                        where candidate.project_id = :projectId
                          and candidate.run_id = :runId
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
                        """)
                .param("now", utc(now))
                .param("projectId", projectId)
                .param("runId", runId)
                .update();
    }

    /** 取消 Run 的未终态任务；PENDING/READY 立即结束，其他任务只打取消标记。 */
    @Override
    public List<Task> requestCancellation(UUID projectId, UUID runId, Instant now) {
        List<UUID> canceledBeforeSubmission = jdbcClient.sql("""
                        update task
                        set cancel_requested = true,
                            status = case when status in ('PENDING', 'READY')
                                then 'CANCELED' else status end,
                            completed_at = case when status in ('PENDING', 'READY')
                                then :now else completed_at end,
                            updated_at = :now, version = version + 1
                        where project_id = :projectId and run_id = :runId
                          and status not in ('SUCCEEDED', 'FAILED', 'CANCELED')
                        returning id, kind, status
                        """)
                .param("projectId", projectId)
                .param("runId", runId)
                .param("now", utc(now))
                .query((rs, row) -> rs.getString("status").equals("CANCELED")
                        && (rs.getString("kind").equals("IMAGE_GENERATION")
                                || rs.getString("kind").equals("VIDEO_GENERATION"))
                        ? rs.getObject("id", UUID.class) : null)
                .list().stream().filter(java.util.Objects::nonNull).toList();
        return canceledBeforeSubmission.stream()
                .map(id -> findById(id).orElseThrow())
                .toList();
    }

    /** 在网络调用前原子写入 SUBMITTING 和 Provider 尝试记录，作为崩溃核对依据。 */
    @Override
    public boolean beginSubmission(UUID taskId, String workerId, long leaseEpoch,
            UUID attemptId, UUID requestKey, String candidateOriginSha256, Instant now) {
        int changed = jdbcClient.sql("""
                        update task set status = 'SUBMITTING', updated_at = :now,
                            version = version + 1
                        where id = :taskId and status = 'RUNNING'
                          and lease_owner = :workerId and lease_epoch = :leaseEpoch
                          and lease_until > :now and cancel_requested = false
                          and exists (select 1 from agent_run r where r.id = task.run_id
                              and r.status not in ('CANCEL_REQUESTED', 'CANCELED'))
                        """)
                .param("taskId", taskId)
                .param("workerId", workerId)
                .param("leaseEpoch", leaseEpoch)
                .param("now", utc(now))
                .update();
        if (changed == 0) {
            return false;
        }
        jdbcClient.sql("""
                        insert into provider_attempt (id, project_id, task_id, lease_epoch,
                            status, request_key, candidate_request_id,
                            candidate_origin_sha256, created_at, updated_at)
                        select :attemptId, project_id, id, :leaseEpoch, 'SUBMITTING',
                            :requestKey, :candidateRequestId, :candidateOriginSha256,
                            :now, :now from task where id = :taskId
                        """)
                .param("attemptId", attemptId)
                .param("taskId", taskId)
                .param("leaseEpoch", leaseEpoch)
                .param("requestKey", requestKey)
                .param("candidateRequestId", candidateOriginSha256 == null ? null : requestKey)
                .param("candidateOriginSha256", candidateOriginSha256)
                .param("now", utc(now))
                .update();
        return true;
    }

    /** 保存 Provider 确认的原请求 ID 并释放提交租约；更新尝试账本必须恰好一行。 */
    @Override
    public boolean acknowledgeSubmission(UUID taskId, String workerId, long leaseEpoch,
            String providerRequestId, Instant nextActionAt, Instant now) {
        int changed = jdbcClient.sql("""
                        update task set status = 'WAITING_PROVIDER',
                            provider_request_id = :requestId,
                            next_action_at = :nextActionAt, lease_owner = null,
                            lease_until = null, updated_at = :now, version = version + 1
                        where id = :taskId and status = 'SUBMITTING'
                          and lease_owner = :workerId and lease_epoch = :leaseEpoch
                          and lease_until > :now
                        """)
                .param("taskId", taskId)
                .param("workerId", workerId)
                .param("leaseEpoch", leaseEpoch)
                .param("requestId", providerRequestId)
                .param("nextActionAt", utc(nextActionAt))
                .param("now", utc(now))
                .update();
        if (changed == 0) {
            return false;
        }
        int attemptChanged = jdbcClient.sql("""
                        update provider_attempt set status = 'ACCEPTED',
                            provider_request_id = :requestId, updated_at = :now
                        where task_id = :taskId and lease_epoch = :leaseEpoch
                          and status = 'SUBMITTING'
                        """)
                .param("taskId", taskId)
                .param("leaseEpoch", leaseEpoch)
                .param("requestId", providerRequestId)
                .param("now", utc(now))
                .update();
        if (attemptChanged != 1) {
            throw new IllegalStateException("Missing provider attempt for submission acknowledgement");
        }
        return true;
    }

    /** 仅当任务版本、UNKNOWN 状态、原候选 ID 和 origin 摘要都匹配时恢复同一请求。 */
    @Override
    public boolean recoverUnknownSubmission(UUID projectId, UUID taskId, long expectedVersion,
            UUID attemptId, UUID candidateRequestId, String candidateOriginSha256, Instant now) {
        int changed = jdbcClient.sql("""
                        update task set status = 'WAITING_PROVIDER',
                            provider_request_id = :requestId, next_action_at = :now,
                            error_code = null, updated_at = :now, version = version + 1
                        where id = :taskId and project_id = :projectId
                          and status = 'UNKNOWN' and version = :expectedVersion
                          and cancel_requested = false and provider_request_id is null
                          and exists (select 1 from agent_run r where r.id = task.run_id
                              and r.status not in ('CANCEL_REQUESTED', 'CANCELED',
                                  'FAILED', 'SUCCEEDED'))
                          and exists (select 1 from provider_attempt pa
                              where pa.id = :attemptId and pa.task_id = task.id
                                and pa.project_id = task.project_id
                                and pa.lease_epoch = task.lease_epoch
                                and pa.status = 'UNKNOWN'
                                and pa.candidate_request_id = :candidateRequestId
                                and pa.candidate_origin_sha256 = :candidateOriginSha256)
                        """)
                .param("taskId", taskId).param("projectId", projectId)
                .param("expectedVersion", expectedVersion)
                .param("attemptId", attemptId)
                .param("candidateRequestId", candidateRequestId)
                .param("candidateOriginSha256", candidateOriginSha256)
                .param("requestId", candidateRequestId.toString())
                .param("now", utc(now)).update();
        if (changed == 0) return false;
        int attemptChanged = jdbcClient.sql("""
                        update provider_attempt set status = 'ACCEPTED',
                            provider_request_id = :requestId, updated_at = :now
                        where id = :attemptId and project_id = :projectId
                          and task_id = :taskId and status = 'UNKNOWN'
                          and candidate_request_id = :candidateRequestId
                          and candidate_origin_sha256 = :candidateOriginSha256
                        """)
                .param("attemptId", attemptId).param("projectId", projectId)
                .param("taskId", taskId).param("candidateRequestId", candidateRequestId)
                .param("candidateOriginSha256", candidateOriginSha256)
                .param("requestId", candidateRequestId.toString())
                .param("now", utc(now)).update();
        if (attemptChanged != 1) {
            throw new IllegalStateException("Original provider attempt disappeared during recovery");
        }
        return true;
    }

    /** 轮询仍未完成时释放租约并安排再次查询，保留原 provider_request_id。 */
    @Override
    public boolean deferProviderPoll(UUID taskId, String workerId, long leaseEpoch,
            Instant nextActionAt, Instant now) {
        return jdbcClient.sql("""
                        update task set status = 'WAITING_PROVIDER',
                            next_action_at = :nextActionAt, lease_owner = null,
                            lease_until = null, updated_at = :now, version = version + 1
                        where id = :taskId and status = 'RUNNING'
                          and provider_request_id is not null
                          and lease_owner = :workerId and lease_epoch = :leaseEpoch
                          and lease_until > :now
                        """)
                .param("taskId", taskId).param("workerId", workerId)
                .param("leaseEpoch", leaseEpoch).param("nextActionAt", utc(nextActionAt))
                .param("now", utc(now)).update() == 1;
    }

    /** 读取原 Provider 请求的连续查询失败次数；尚无账本行时返回零。 */
    @Override
    public int providerPollFailureCount(UUID taskId) {
        return jdbcClient.sql("""
                        select failure_count from task_provider_poll_retry where task_id = :taskId
                        """)
                .param("taskId", taskId).query(Integer.class).optional().orElse(0);
    }

    /** 以任务为唯一键写入连续查询失败计数和最近稳定错误码。 */
    @Override
    public void recordProviderPollFailure(UUID taskId, int count, String errorCode, Instant now) {
        int changed = jdbcClient.sql("""
                        insert into task_provider_poll_retry
                            (task_id, failure_count, last_error_code, updated_at)
                        values (:taskId, :count, :errorCode, :now)
                        on conflict (task_id) do update set
                            failure_count = excluded.failure_count,
                            last_error_code = excluded.last_error_code,
                            updated_at = excluded.updated_at
                        """)
                .param("taskId", taskId).param("count", count)
                .param("errorCode", errorCode).param("now", utc(now)).update();
        if (changed != 1) throw new IllegalStateException("Provider poll retry ledger did not update");
    }

    /** 查询成功或归档完成后清除该请求的连续失败计数。 */
    @Override
    public void clearProviderPollFailures(UUID taskId) {
        jdbcClient.sql("delete from task_provider_poll_retry where task_id = :taskId")
                .param("taskId", taskId).update();
    }

    /** 原请求保留但配置不再安全时阻断轮询，并释放当前租约。 */
    @Override
    public boolean blockProviderPoll(UUID taskId, String workerId, long leaseEpoch,
            String errorCode, Instant now) {
        return jdbcClient.sql("""
                        update task set status = 'BLOCKED', error_code = :errorCode,
                            lease_owner = null, lease_until = null,
                            updated_at = :now, version = version + 1
                        where id = :taskId and status = 'RUNNING'
                          and provider_request_id is not null
                          and lease_owner = :workerId and lease_epoch = :leaseEpoch
                          and lease_until > :now
                        """)
                .param("taskId", taskId).param("workerId", workerId)
                .param("leaseEpoch", leaseEpoch).param("errorCode", errorCode)
                .param("now", utc(now)).update() == 1;
    }

    /** 无锁、有界发现已过期的提交检查点；实际转换由后续条件更新完成。 */
    @Override
    public List<ExpiredSubmission> findExpiredSubmissions(Instant now, int limit) {
        return jdbcClient.sql("""
                        select t.id, t.project_id, p.owner_id from task t
                        join project p on p.id = t.project_id
                        where t.status = 'SUBMITTING' and t.lease_until <= :now
                        order by t.lease_until, t.id limit :limit
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .query((rs, row) -> new ExpiredSubmission(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("owner_id", UUID.class)))
                .list();
    }

    /** 仅当提交租约仍已过期且状态仍为 SUBMITTING 时转成 UNKNOWN。 */
    @Override
    public boolean recoverExpiredSubmission(UUID taskId, Instant now) {
        int changed = jdbcClient.sql("""
                        update task set status = 'UNKNOWN', lease_owner = null,
                            lease_until = null, error_code = 'PROVIDER_SUBMISSION_UNKNOWN',
                            updated_at = :now, version = version + 1
                        where id = :taskId and status = 'SUBMITTING'
                          and lease_until <= :now
                        """)
                .param("taskId", taskId)
                .param("now", utc(now))
                .update();
        if (changed == 0) {
            return false;
        }
        int attemptChanged = jdbcClient.sql("""
                        update provider_attempt set status = 'UNKNOWN', updated_at = :now
                        where task_id = :taskId and status = 'SUBMITTING'
                        """)
                .param("taskId", taskId)
                .param("now", utc(now))
                .update();
        if (attemptChanged != 1) {
            throw new IllegalStateException("Missing provider attempt for recovered submission");
        }
        return true;
    }

    /** 查找已请求取消且 RUNNING 租约过期的本地任务，不推断 Provider 已取消。 */
    @Override
    public List<ExpiredSubmission> findExpiredCanceledRunning(Instant now, int limit) {
        return jdbcClient.sql("""
                        select t.id, t.project_id, p.owner_id from task t
                        join project p on p.id = t.project_id
                        where t.status = 'RUNNING' and t.cancel_requested = true
                          and t.lease_until <= :now
                        order by t.lease_until, t.id limit :limit
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .query((rs, row) -> new ExpiredSubmission(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("owner_id", UUID.class)))
                .list();
    }

    /** 仅关闭取消请求已持久化且租约仍过期的本地任务。 */
    @Override
    public boolean finishExpiredCanceledRunning(UUID taskId, Instant now) {
        return jdbcClient.sql("""
                        update task set status = 'CANCELED', lease_owner = null,
                            lease_until = null, completed_at = :now, updated_at = :now,
                            version = version + 1
                        where id = :taskId and status = 'RUNNING'
                          and cancel_requested = true and lease_until <= :now
                        """)
                .param("taskId", taskId)
                .param("now", utc(now))
                .update() == 1;
    }

    /** 将晚到输出按 taskId 与 epoch 唯一保存到旁路历史，不覆盖主任务输出。 */
    @Override
    public boolean recordLateResult(Task lease, JsonNode output, Instant now) {
        return jdbcClient.sql("""
                        insert into task_late_result (id, project_id, task_id, lease_epoch,
                            output_json, created_at)
                        select :id, project_id, id, :leaseEpoch, cast(:output as jsonb), :now
                        from task where id = :taskId and lease_epoch = :leaseEpoch
                          and cancel_requested = true
                        on conflict (task_id, lease_epoch) do nothing
                        """)
                .param("id", UUID.randomUUID())
                .param("taskId", lease.id())
                .param("leaseEpoch", lease.leaseEpoch())
                .param("output", output == null ? "null" : output.toString())
                .param("now", utc(now))
                .update() == 1;
    }

    /** 晚到结果归档后终结取消任务；接受仍持有原 epoch 的 Worker 或已释放的 UNKNOWN。 */
    @Override
    public boolean finishCanceled(Task lease, String workerId, Instant now) {
        return jdbcClient.sql("""
                        update task set status = 'CANCELED', lease_owner = null,
                            lease_until = null, completed_at = :now, updated_at = :now,
                            version = version + 1
                        where id = :taskId and lease_epoch = :leaseEpoch
                          and cancel_requested = true
                          and ((status in ('RUNNING', 'SUBMITTING') and lease_owner = :workerId)
                            or (status = 'UNKNOWN' and lease_owner is null))
                        """)
                .param("taskId", lease.id())
                .param("leaseEpoch", lease.leaseEpoch())
                .param("workerId", workerId)
                .param("now", utc(now))
                .update() == 1;
    }

    /** 同步生成结果须在提交租约仍有效时完成任务，并把对应 Provider 尝试记为已受理。 */
    @Override
    public boolean finishSubmitting(Task lease, String workerId, JsonNode output, Instant now) {
        int changed = jdbcClient.sql("""
                        update task set status = 'SUCCEEDED', output_json = cast(:output as jsonb),
                            lease_owner = null, lease_until = null, completed_at = :now,
                            updated_at = :now, version = version + 1
                        where id = :taskId and status = 'SUBMITTING'
                          and lease_owner = :workerId and lease_epoch = :leaseEpoch
                          and lease_until > :now and cancel_requested = false
                        """)
                .param("taskId", lease.id())
                .param("workerId", workerId)
                .param("leaseEpoch", lease.leaseEpoch())
                .param("output", output.toString())
                .param("now", utc(now))
                .update();
        if (changed == 0) {
            return false;
        }
        int attemptChanged = jdbcClient.sql("""
                        update provider_attempt set status = 'ACCEPTED', updated_at = :now
                        where task_id = :taskId and lease_epoch = :leaseEpoch
                          and status = 'SUBMITTING'
                        """)
                .param("taskId", lease.id())
                .param("leaseEpoch", lease.leaseEpoch())
                .param("now", utc(now))
                .update();
        if (attemptChanged != 1) {
            throw new IllegalStateException("Missing provider attempt for completed submission");
        }
        return true;
    }

    /** 只完成与当前任务保存的原 requestId 相同的轮询结果。 */
    @Override
    public boolean finishProviderResult(Task lease, String workerId, JsonNode output, Instant now) {
        return jdbcClient.sql("""
                        update task set status = 'SUCCEEDED', output_json = cast(:output as jsonb),
                            lease_owner = null, lease_until = null, completed_at = :now,
                            updated_at = :now, version = version + 1
                        where id = :taskId and status = 'RUNNING'
                          and provider_request_id = :requestId
                          and lease_owner = :workerId and lease_epoch = :leaseEpoch
                          and lease_until > :now and cancel_requested = false
                        """)
                .param("taskId", lease.id()).param("requestId", lease.providerRequestId())
                .param("workerId", workerId).param("leaseEpoch", lease.leaseEpoch())
                .param("output", output.toString()).param("now", utc(now)).update() == 1;
    }

    /** Provider 明确拒绝时终结任务并标记尝试 REJECTED；超时不得调用此转换。 */
    @Override
    public boolean rejectSubmission(Task lease, String workerId, String errorCode, Instant now) {
        int changed = jdbcClient.sql("""
                        update task set status = case when cancel_requested
                                then 'CANCELED' else 'FAILED' end,
                            error_code = :errorCode, lease_owner = null, lease_until = null,
                            completed_at = :now, updated_at = :now, version = version + 1
                        where id = :taskId and kind in ('IMAGE_GENERATION', 'VIDEO_GENERATION')
                          and status = 'SUBMITTING' and lease_owner = :workerId
                          and lease_epoch = :leaseEpoch and lease_until > :now
                        """)
                .param("taskId", lease.id())
                .param("workerId", workerId)
                .param("leaseEpoch", lease.leaseEpoch())
                .param("errorCode", errorCode)
                .param("now", utc(now))
                .update();
        if (changed == 0) {
            return false;
        }
        int attemptChanged = jdbcClient.sql("""
                        update provider_attempt set status = 'REJECTED', updated_at = :now
                        where task_id = :taskId and lease_epoch = :leaseEpoch
                          and status = 'SUBMITTING'
                        """)
                .param("taskId", lease.id())
                .param("leaseEpoch", lease.leaseEpoch())
                .param("now", utc(now))
                .update();
        if (attemptChanged != 1) {
            throw new IllegalStateException("Missing provider attempt for confirmed rejection");
        }
        return true;
    }

    /** 统一限定后续查询可读取的任务字段，并将 JSONB 转成 Jackson 可读文本。 */
    private String selectProjection() {
        return """
                select t.id, t.project_id, t.run_id, t.plan_id, t.step_key,
                       t.kind, t.status, t.cancel_requested,
                       t.input_json::text as input_json,
                       t.input_hash, t.output_json::text as output_json,
                       t.provider_id, t.provider_request_id, t.attempt_no,
                       t.next_action_at, t.lease_owner, t.lease_until,
                       t.lease_epoch, t.version, t.error_code,
                       t.created_at, t.updated_at, t.completed_at
                from task t
                """;
    }

    /** 将业务 Instant 显式转换为 PostgreSQL JDBC UTC 偏移时间。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
