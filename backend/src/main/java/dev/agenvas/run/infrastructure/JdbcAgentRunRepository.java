package dev.agenvas.run.infrastructure;

import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.domain.AgentRun;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL Run repository; all resource reads retain the project owner boundary. */
@Repository
public class JdbcAgentRunRepository implements AgentRunRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final RowMapper<AgentRun> runMapper;

    public JdbcAgentRunRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.runMapper = (resultSet, rowNumber) -> new AgentRun(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("agent_instance_id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                AgentRun.Status.valueOf(resultSet.getString("status")),
                resultSet.getString("instruction"),
                objectMapper.readTree(resultSet.getString("context_snapshot_json")),
                objectMapper.readTree(resultSet.getString("policy_snapshot_json")),
                resultSet.getInt("profile_version"),
                resultSet.getInt("next_step_index"),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(resultSet.getObject("completed_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant)
                        .orElse(null));
    }

    @Override
    public boolean reserveIdempotency(
            UUID principalId,
            String scope,
            String key,
            String requestHash,
            Instant expiresAt,
            Instant now) {
        return jdbcClient.sql("""
                        insert into idempotency_record (
                            principal_id, scope, idempotency_key, request_hash, state,
                            resource_id, response_json, expires_at, created_at, updated_at
                        ) values (
                            :principalId, :scope, :key, :requestHash, 'IN_PROGRESS',
                            null, null, :expiresAt, :now, :now
                        )
                        on conflict (principal_id, scope, idempotency_key) do nothing
                        """)
                .param("principalId", principalId)
                .param("scope", scope)
                .param("key", key)
                .param("requestHash", requestHash)
                .param("expiresAt", utc(expiresAt))
                .param("now", utc(now))
                .update() == 1;
    }

    @Override
    public Optional<IdempotencyRecord> findIdempotency(
            UUID principalId, String scope, String key) {
        return jdbcClient.sql("""
                        select request_hash, state, resource_id, response_json::text as response_json,
                               expires_at
                        from idempotency_record
                        where principal_id = :principalId and scope = :scope
                          and idempotency_key = :key
                        """)
                .param("principalId", principalId)
                .param("scope", scope)
                .param("key", key)
                .query((resultSet, rowNumber) -> new IdempotencyRecord(
                        resultSet.getString("request_hash"),
                        IdempotencyRecord.State.valueOf(resultSet.getString("state")),
                        resultSet.getObject("resource_id", UUID.class),
                        resultSet.getString("response_json"),
                        resultSet.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public boolean completeIdempotency(
            UUID principalId,
            String scope,
            String key,
            String requestHash,
            UUID resourceId,
            String responseJson,
            Instant now) {
        return jdbcClient.sql("""
                        update idempotency_record
                        set state = 'COMPLETED', resource_id = :resourceId,
                            response_json = cast(:responseJson as jsonb), updated_at = :now
                        where principal_id = :principalId and scope = :scope
                          and idempotency_key = :key and request_hash = :requestHash
                          and state = 'IN_PROGRESS'
                        """)
                .param("resourceId", resourceId)
                .param("responseJson", responseJson)
                .param("now", utc(now))
                .param("principalId", principalId)
                .param("scope", scope)
                .param("key", key)
                .param("requestHash", requestHash)
                .update() == 1;
    }

    @Override
    public void create(AgentRun run) {
        jdbcClient.sql("""
                        insert into agent_run (
                            id, project_id, agent_instance_id, user_id, status, instruction,
                            context_snapshot_json, policy_snapshot_json, profile_version,
                            next_step_index, version, created_at, updated_at, completed_at
                        ) values (
                            :id, :projectId, :agentId, :userId, :status, :instruction,
                            cast(:contextSnapshot as jsonb), cast(:policySnapshot as jsonb),
                            :profileVersion, :nextStepIndex, :version, :createdAt, :updatedAt, null
                        )
                        """)
                .param("id", run.id())
                .param("projectId", run.projectId())
                .param("agentId", run.agentInstanceId())
                .param("userId", run.userId())
                .param("status", run.status().name())
                .param("instruction", run.instruction())
                .param("contextSnapshot", run.contextSnapshot().toString())
                .param("policySnapshot", run.policySnapshot().toString())
                .param("profileVersion", run.profileVersion())
                .param("nextStepIndex", run.nextStepIndex())
                .param("version", run.version())
                .param("createdAt", utc(run.createdAt()))
                .param("updatedAt", utc(run.updatedAt()))
                .update();
    }

    @Override
    public Optional<AgentRun> find(UUID ownerId, UUID projectId, UUID runId) {
        return find(ownerId, projectId, runId, false);
    }

    @Override
    public List<AgentRun> list(UUID ownerId, UUID projectId, UUID agentId,
            Instant beforeCreatedAt, UUID beforeId, int limit) {
        String cursorPredicate = beforeCreatedAt == null
                ? ""
                : "and (ar.created_at, ar.id) < (:beforeCreatedAt, :beforeId)";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        select ar.id, ar.project_id, ar.agent_instance_id, ar.user_id,
                               ar.status, ar.instruction,
                               ar.context_snapshot_json::text as context_snapshot_json,
                               ar.policy_snapshot_json::text as policy_snapshot_json,
                               ar.profile_version, ar.next_step_index, ar.version,
                               ar.created_at, ar.updated_at, ar.completed_at
                        from agent_run ar
                        join project p on p.id = ar.project_id
                        where p.owner_id = :ownerId and ar.project_id = :projectId
                          and ar.agent_instance_id = :agentId
                          """ + cursorPredicate + """
                        order by ar.created_at desc, ar.id desc
                        limit :limit
                        """)
                .param("ownerId", ownerId)
                .param("projectId", projectId)
                .param("agentId", agentId)
                .param("limit", limit);
        if (beforeCreatedAt != null) {
            statement = statement.param("beforeCreatedAt", utc(beforeCreatedAt))
                    .param("beforeId", beforeId);
        }
        return statement.query(runMapper).list();
    }

    @Override
    public Optional<AgentRun> findForUpdate(UUID ownerId, UUID projectId, UUID runId) {
        return find(ownerId, projectId, runId, true);
    }

    private Optional<AgentRun> find(
            UUID ownerId, UUID projectId, UUID runId, boolean forUpdate) {
        String lockClause = forUpdate ? " for update of ar" : "";
        return jdbcClient.sql("""
                        select ar.id, ar.project_id, ar.agent_instance_id, ar.user_id,
                               ar.status, ar.instruction,
                               ar.context_snapshot_json::text as context_snapshot_json,
                               ar.policy_snapshot_json::text as policy_snapshot_json,
                               ar.profile_version, ar.next_step_index, ar.version,
                               ar.created_at, ar.updated_at, ar.completed_at
                        from agent_run ar
                        join project p on p.id = ar.project_id
                        where ar.id = :runId and ar.project_id = :projectId
                          and p.owner_id = :ownerId
                        """ + lockClause)
                .param("runId", runId)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .query(runMapper)
                .optional();
    }

    @Override
    public boolean updateStatus(
            UUID ownerId,
            UUID projectId,
            UUID runId,
            long expectedVersion,
            AgentRun.Status status,
            Instant updatedAt,
            Instant completedAt) {
        return jdbcClient.sql("""
                        update agent_run ar
                        set status = :status,
                            version = ar.version + 1,
                            updated_at = :updatedAt,
                            completed_at = :completedAt
                        from project p
                        where ar.id = :runId and ar.project_id = :projectId
                          and p.id = ar.project_id and p.owner_id = :ownerId
                          and ar.version = :expectedVersion
                        """)
                .param("status", status.name())
                .param("updatedAt", utc(updatedAt))
                .param("completedAt", completedAt == null ? null : utc(completedAt), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("runId", runId)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    @Override
    public boolean advanceStep(UUID ownerId, UUID projectId, UUID runId,
            long expectedVersion, int expectedStepIndex, Instant updatedAt) {
        return jdbcClient.sql("""
                        update agent_run ar
                        set next_step_index = ar.next_step_index + 1,
                            version = ar.version + 1,
                            updated_at = :updatedAt
                        from project p
                        where ar.id = :runId and ar.project_id = :projectId
                          and p.id = ar.project_id and p.owner_id = :ownerId
                          and ar.status in ('RUNNING', 'WAITING_APPROVAL')
                          and ar.version = :expectedVersion
                          and ar.next_step_index = :expectedStepIndex
                        """)
                .param("updatedAt", utc(updatedAt))
                .param("runId", runId)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .param("expectedVersion", expectedVersion)
                .param("expectedStepIndex", expectedStepIndex)
                .update() == 1;
    }

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
