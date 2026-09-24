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

/** 负责 Run 与幂等记录的 PostgreSQL 读写；所有资源查询都联结项目校验所有者。 */
@Repository
public class JdbcAgentRunRepository implements AgentRunRepository {

    /** 执行带所有者和项目边界的 Run 与幂等记录 SQL。 */
    private final JdbcClient jdbcClient;
    /** 将冻结的上下文策略 JSON 与数据库行互相转换。 */
    private final ObjectMapper objectMapper;
    /** 将 ResultSet 还原为领域 Run，包括快照和完成时间。 */
    private final RowMapper<AgentRun> runMapper;

    /** 初始化 Run 行映射；映射保留创建时冻结的上下文与策略快照。 */
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

    /** 以用户、操作范围和幂等键为唯一身份预留请求；冲突时不覆盖已有记录。 */
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

    /** 读取幂等请求的摘要、状态和原响应，用于区分重放、进行中及载荷冲突。 */
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

    /** 仅把匹配摘要且仍处于进行中的记录提交为完成状态。 */
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

    /** 保存 Run 创建时冻结的上下文、策略、配置版本及初始步骤序号。 */
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

    /** 以所有者、项目和 Run 三重范围读取，不取得写锁。 */
    @Override
    public Optional<AgentRun> find(UUID ownerId, UUID projectId, UUID runId) {
        return find(ownerId, projectId, runId, false);
    }

    /** 按创建时间与 ID 组成稳定游标倒序分页，避免同时间记录被跳过。 */
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

    /** 对目标 Run 行加锁，供状态机先读后写的事务使用。 */
    @Override
    public Optional<AgentRun> findForUpdate(UUID ownerId, UUID projectId, UUID runId) {
        return find(ownerId, projectId, runId, true);
    }

    /** 普通读取与行锁读取共用同一所有者边界，锁仅作用于 Run 行。 */
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

    /** 通过版本比较实现并发安全的状态写入；零行更新表示版本已变化或越权。 */
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

    /** 仅在 Run 状态、版本和当前步骤都匹配时递增步骤与版本。 */
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

    /** 将绝对时刻绑定为 JDBC 使用的 UTC offset datetime。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
