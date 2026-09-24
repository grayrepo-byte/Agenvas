package dev.agenvas.task.infrastructure;

import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.domain.Task;
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

/** PostgreSQL Task queue using short SKIP LOCKED claims and lease-epoch fencing. */
@Repository
public class JdbcTaskRepository implements TaskRepository, RunTaskCancellation, RunTaskCreation {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final RowMapper<Task> taskMapper;

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

    private ManualReplacement manualReplacement(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ManualReplacement(rs.getObject("project_id", UUID.class),
                rs.getObject("original_task_id", UUID.class),
                rs.getObject("replacement_task_id", UUID.class),
                rs.getObject("approved_by_user_id", UUID.class),
                rs.getLong("original_task_version"), rs.getString("idempotency_key"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

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

    /** Writes a single runnable model step in the caller's Run creation transaction. */
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

    @Override
    public Optional<Task> findById(UUID taskId) {
        return jdbcClient.sql(selectProjection() + "where t.id = :taskId")
                .param("taskId", taskId)
                .query(taskMapper)
                .optional();
    }

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

    @Override
    public long countByRunAndKind(UUID projectId, UUID runId, Task.Kind kind) {
        return jdbcClient.sql("""
                        select count(*) from task where project_id = :projectId
                          and run_id = :runId and kind = :kind
                        """)
                .param("projectId", projectId).param("runId", runId)
                .param("kind", kind.name()).query(Long.class).single();
    }

    @Override
    public List<Task> claimDue(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil, null);
    }

    @Override
    public List<Task> claimDueImages(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil, Task.Kind.IMAGE_GENERATION);
    }

    @Override
    public List<Task> claimDueComfyImage(String workerId, Instant now, Instant leaseUntil) {
        return claimDueComfy(workerId, now, leaseUntil, Task.Kind.IMAGE_GENERATION);
    }

    @Override
    public List<Task> claimDueComfyVideo(String workerId, Instant now, Instant leaseUntil) {
        return claimDueComfy(workerId, now, leaseUntil, Task.Kind.VIDEO_GENERATION);
    }

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

    @Override
    public List<Task> claimDueVideos(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil, Task.Kind.VIDEO_GENERATION);
    }

    @Override
    public List<Task> claimDueProviderPolls(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimProviderPolls(workerId, limit, now, leaseUntil, null);
    }

    @Override
    public List<Task> claimDueComfyImagePolls(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimProviderPolls(workerId, limit, now, leaseUntil,
                Task.Kind.IMAGE_GENERATION);
    }

    @Override
    public List<Task> claimDueComfyVideoPolls(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimProviderPolls(workerId, limit, now, leaseUntil,
                Task.Kind.VIDEO_GENERATION);
    }

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

    @Override
    public List<Task> claimDueAgentTurns(
            String workerId, int limit, Instant now, Instant leaseUntil) {
        return claimDueKind(workerId, limit, now, leaseUntil, Task.Kind.AGENT_TURN);
    }

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

    /** Keeps model and media workers in disjoint SKIP LOCKED claim domains. */
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

    @Override
    public int providerPollFailureCount(UUID taskId) {
        return jdbcClient.sql("""
                        select failure_count from task_provider_poll_retry where task_id = :taskId
                        """)
                .param("taskId", taskId).query(Integer.class).optional().orElse(0);
    }

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

    @Override
    public void clearProviderPollFailures(UUID taskId) {
        jdbcClient.sql("delete from task_provider_poll_retry where task_id = :taskId")
                .param("taskId", taskId).update();
    }

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

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
