package dev.agenvas.run.infrastructure;

import static dev.agenvas.db.Tables.AGENT_RUN;
import static dev.agenvas.db.Tables.IDEMPOTENCY_RECORD;
import static dev.agenvas.db.Tables.PROJECT;

import dev.agenvas.db.tables.records.AgentRunRecord;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.application.BlockedRunCandidate;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.idempotency.IdempotencyState;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** 负责 Run 与幂等记录的 PostgreSQL 读写；所有资源查询都联结项目校验所有者。 */
@Repository
public class JooqAgentRunRepository implements AgentRunRepository {

    @Override
    public List<BlockedRunCandidate> blockedRecoveryCandidates(UUID afterId, int limit) {
        return dsl.select(PROJECT.OWNER_ID, AGENT_RUN.PROJECT_ID, AGENT_RUN.ID)
                .from(AGENT_RUN)
                .join(PROJECT).on(PROJECT.ID.eq(AGENT_RUN.PROJECT_ID))
                .where(AGENT_RUN.STATUS.eq(AgentRun.Status.BLOCKED.name()))
                .and(PROJECT.ACTIVE_RUN_ID.eq(AGENT_RUN.ID))
                .and(afterId == null ? DSL.noCondition() : AGENT_RUN.ID.gt(afterId))
                .orderBy(AGENT_RUN.ID).limit(limit)
                .fetch(row -> new BlockedRunCandidate(row.get(PROJECT.OWNER_ID),
                        row.get(AGENT_RUN.PROJECT_ID), row.get(AGENT_RUN.ID)));
    }

    @Override
    public List<AgentRun> stopForHistoryCleanup(List<UUID> runIds, Instant now) {
        return dsl.update(AGENT_RUN).set(AGENT_RUN.STATUS, AgentRun.Status.CANCELED.name())
                .set(AGENT_RUN.VERSION, AGENT_RUN.VERSION.plus(1))
                .set(AGENT_RUN.COMPLETED_AT, utc(now)).set(AGENT_RUN.UPDATED_AT, utc(now))
                .where(AGENT_RUN.ID.in(runIds))
                .and(AGENT_RUN.STATUS.notIn(AgentRun.Status.SUCCEEDED.name(), AgentRun.Status.FAILED.name(), AgentRun.Status.CANCELED.name()))
                .returning().fetch(this::map);
    }

    /** 执行带所有者和项目边界的 Run 与幂等记录查询。 */
    private final DSLContext dsl;
    /** 将冻结的上下文策略 JSON 与数据库行互相转换。 */
    private final ObjectMapper objectMapper;

    /** 初始化 Run 仓储；映射保留创建时冻结的上下文与策略快照。 */
    public JooqAgentRunRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
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
        return dsl.insertInto(IDEMPOTENCY_RECORD)
                .set(IDEMPOTENCY_RECORD.PRINCIPAL_ID, principalId)
                .set(IDEMPOTENCY_RECORD.SCOPE, scope)
                .set(IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY, key)
                .set(IDEMPOTENCY_RECORD.REQUEST_HASH, requestHash)
                .set(IDEMPOTENCY_RECORD.STATE, IdempotencyState.IN_PROGRESS.name())
                .setNull(IDEMPOTENCY_RECORD.RESOURCE_ID)
                .setNull(IDEMPOTENCY_RECORD.RESPONSE_JSON)
                .set(IDEMPOTENCY_RECORD.EXPIRES_AT, utc(expiresAt))
                .set(IDEMPOTENCY_RECORD.CREATED_AT, utc(now))
                .set(IDEMPOTENCY_RECORD.UPDATED_AT, utc(now))
                .onConflict(IDEMPOTENCY_RECORD.PRINCIPAL_ID, IDEMPOTENCY_RECORD.SCOPE,
                        IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY)
                .doNothing()
                .execute() == 1;
    }

    /** 读取幂等请求的摘要、状态和原响应，用于区分重放、进行中及载荷冲突。 */
    @Override
    public Optional<IdempotencyRecord> findIdempotency(
            UUID principalId, String scope, String key) {
        return dsl.select(IDEMPOTENCY_RECORD.REQUEST_HASH, IDEMPOTENCY_RECORD.STATE,
                        IDEMPOTENCY_RECORD.RESOURCE_ID, IDEMPOTENCY_RECORD.RESPONSE_JSON,
                        IDEMPOTENCY_RECORD.EXPIRES_AT)
                .from(IDEMPOTENCY_RECORD)
                .where(IDEMPOTENCY_RECORD.PRINCIPAL_ID.eq(principalId))
                .and(IDEMPOTENCY_RECORD.SCOPE.eq(scope))
                .and(IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(row -> new IdempotencyRecord(
                        row.get(IDEMPOTENCY_RECORD.REQUEST_HASH),
                        IdempotencyState.valueOf(row.get(IDEMPOTENCY_RECORD.STATE)),
                        row.get(IDEMPOTENCY_RECORD.RESOURCE_ID),
                        row.get(IDEMPOTENCY_RECORD.RESPONSE_JSON) == null ? null
                                : row.get(IDEMPOTENCY_RECORD.RESPONSE_JSON).data(),
                        row.get(IDEMPOTENCY_RECORD.EXPIRES_AT).toInstant()));
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
        return dsl.update(IDEMPOTENCY_RECORD)
                .set(IDEMPOTENCY_RECORD.STATE, IdempotencyState.COMPLETED.name())
                .set(IDEMPOTENCY_RECORD.RESOURCE_ID, resourceId)
                .set(IDEMPOTENCY_RECORD.RESPONSE_JSON, JSONB.valueOf(responseJson))
                .set(IDEMPOTENCY_RECORD.UPDATED_AT, utc(now))
                .where(IDEMPOTENCY_RECORD.PRINCIPAL_ID.eq(principalId))
                .and(IDEMPOTENCY_RECORD.SCOPE.eq(scope))
                .and(IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY.eq(key))
                .and(IDEMPOTENCY_RECORD.REQUEST_HASH.eq(requestHash))
                .and(IDEMPOTENCY_RECORD.STATE.eq(IdempotencyState.IN_PROGRESS.name()))
                .execute() == 1;
    }

    /** 保存 Run 创建时冻结的上下文、策略、配置版本及初始步骤序号。 */
    @Override
    public void create(AgentRun run) {
        dsl.insertInto(AGENT_RUN)
                .set(AGENT_RUN.ID, run.id())
                .set(AGENT_RUN.PROJECT_ID, run.projectId())
                .set(AGENT_RUN.AGENT_INSTANCE_ID, run.agentInstanceId())
                .set(AGENT_RUN.CONVERSATION_ID, run.conversationId())
                .set(AGENT_RUN.CONVERSATION_TURN, run.conversationTurn())
                .set(AGENT_RUN.USER_ID, run.userId())
                .set(AGENT_RUN.STATUS, run.status().name())
                .set(AGENT_RUN.INSTRUCTION, run.instruction())
                .set(AGENT_RUN.CONTEXT_SNAPSHOT_JSON, JSONB.valueOf(run.contextSnapshot().toString()))
                .set(AGENT_RUN.POLICY_SNAPSHOT_JSON, JSONB.valueOf(run.policySnapshot().toString()))
                .set(AGENT_RUN.PROFILE_VERSION, run.profileVersion())
                .set(AGENT_RUN.NEXT_STEP_INDEX, run.nextStepIndex())
                .set(AGENT_RUN.VERSION, run.version())
                .set(AGENT_RUN.CREATED_AT, utc(run.createdAt()))
                .set(AGENT_RUN.UPDATED_AT, utc(run.updatedAt()))
                .setNull(AGENT_RUN.COMPLETED_AT)
                .execute();
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
        Condition boundary = PROJECT.OWNER_ID.eq(ownerId)
                .and(AGENT_RUN.PROJECT_ID.eq(projectId))
                .and(AGENT_RUN.AGENT_INSTANCE_ID.eq(agentId));
        if (beforeCreatedAt != null) {
            boundary = boundary.and(DSL.row(AGENT_RUN.CREATED_AT, AGENT_RUN.ID)
                    .lt(utc(beforeCreatedAt), beforeId));
        }
        return dsl.select(AGENT_RUN.fields())
                .from(AGENT_RUN)
                .join(PROJECT).on(PROJECT.ID.eq(AGENT_RUN.PROJECT_ID))
                .where(boundary)
                .orderBy(AGENT_RUN.CREATED_AT.desc(), AGENT_RUN.ID.desc())
                .limit(limit)
                .fetch(record -> map(record.into(AGENT_RUN)));
    }

    @Override
    public List<AgentRun> listConversation(UUID ownerId, UUID projectId, UUID agentId,
            UUID conversationId, Long beforeTurn, int limit) {
        Condition boundary = PROJECT.OWNER_ID.eq(ownerId)
                .and(AGENT_RUN.PROJECT_ID.eq(projectId))
                .and(AGENT_RUN.AGENT_INSTANCE_ID.eq(agentId))
                .and(AGENT_RUN.CONVERSATION_ID.eq(conversationId));
        if (beforeTurn != null) {
            boundary = boundary.and(AGENT_RUN.CONVERSATION_TURN.lt(beforeTurn));
        }
        return dsl.select(AGENT_RUN.fields())
                .from(AGENT_RUN)
                .join(PROJECT).on(PROJECT.ID.eq(AGENT_RUN.PROJECT_ID))
                .where(boundary)
                .orderBy(AGENT_RUN.CONVERSATION_TURN.desc())
                .limit(limit)
                .fetch(record -> map(record.into(AGENT_RUN)));
    }

    /**
     * 保留首轮与最近终态 Run 的会话序号。
     *
     * <p>该查询是带括号有序 UNION 分支的 CTE（{@code (select ... order by ... limit 1) union (...)}），
     * jOOQ DSL 无法表达分支级 ORDER BY/LIMIT，因此保留 SQL 文本，仍通过 {@link DSLContext} 执行；
     * 参数顺序与原来的命名参数一一对应。
     */
    @Override
    public List<UUID> contextRunIds(UUID projectId, UUID conversationId, long throughTurn, int limit) {
        return dsl.resultQuery("""
                with prior as (
                    select id, conversation_turn from agent_run
                    where project_id = ? and conversation_id = ?
                      and conversation_turn <= ?
                      and status in ('SUCCEEDED', 'CANCELED', 'FAILED')
                ), selected as (
                    (select * from prior order by conversation_turn asc limit 1)
                    union
                    (select * from prior order by conversation_turn desc limit ?)
                )
                select id from selected order by conversation_turn
                """, projectId, conversationId, throughTurn, limit - 1)
                .fetch(record -> record.get("id", UUID.class));
    }

    @Override
    public long contextRunCount(UUID projectId, UUID conversationId, long throughTurn) {
        return dsl.fetchCount(AGENT_RUN, AGENT_RUN.PROJECT_ID.eq(projectId)
                .and(AGENT_RUN.CONVERSATION_ID.eq(conversationId))
                .and(AGENT_RUN.CONVERSATION_TURN.le(throughTurn))
                .and(AGENT_RUN.STATUS.in(AgentRun.Status.SUCCEEDED.name(),
                        AgentRun.Status.CANCELED.name(), AgentRun.Status.FAILED.name())));
    }

    /** 对目标 Run 行加锁，供状态机先读后写的事务使用。 */
    @Override
    public Optional<AgentRun> findForUpdate(UUID ownerId, UUID projectId, UUID runId) {
        return find(ownerId, projectId, runId, true);
    }

    /** 普通读取与行锁读取共用同一所有者边界，锁仅作用于 Run 行。 */
    private Optional<AgentRun> find(
            UUID ownerId, UUID projectId, UUID runId, boolean forUpdate) {
        var query = dsl.select(AGENT_RUN.fields())
                .from(AGENT_RUN)
                .join(PROJECT).on(PROJECT.ID.eq(AGENT_RUN.PROJECT_ID))
                .where(AGENT_RUN.ID.eq(runId))
                .and(AGENT_RUN.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId));
        return forUpdate
                ? query.forUpdate().of(AGENT_RUN).fetchOptional(record -> map(record.into(AGENT_RUN)))
                : query.fetchOptional(record -> map(record.into(AGENT_RUN)));
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
        return dsl.update(AGENT_RUN)
                .set(AGENT_RUN.STATUS, status.name())
                .set(AGENT_RUN.VERSION, AGENT_RUN.VERSION.add(1))
                .set(AGENT_RUN.UPDATED_AT, utc(updatedAt))
                .set(AGENT_RUN.COMPLETED_AT, completedAt == null ? null : utc(completedAt))
                .where(AGENT_RUN.ID.eq(runId))
                .and(AGENT_RUN.PROJECT_ID.eq(projectId))
                .and(AGENT_RUN.VERSION.eq(expectedVersion))
                .and(ownedProject(ownerId))
                .execute() == 1;
    }

    /** 仅在 Run 状态、版本和当前步骤都匹配时递增步骤与版本。 */
    @Override
    public boolean advanceStep(UUID ownerId, UUID projectId, UUID runId,
            long expectedVersion, int expectedStepIndex, Instant updatedAt) {
        return dsl.update(AGENT_RUN)
                .set(AGENT_RUN.NEXT_STEP_INDEX, AGENT_RUN.NEXT_STEP_INDEX.add(1))
                .set(AGENT_RUN.VERSION, AGENT_RUN.VERSION.add(1))
                .set(AGENT_RUN.UPDATED_AT, utc(updatedAt))
                .where(AGENT_RUN.ID.eq(runId))
                .and(AGENT_RUN.PROJECT_ID.eq(projectId))
                .and(AGENT_RUN.STATUS.eq(AgentRun.Status.RUNNING.name()))
                .and(AGENT_RUN.VERSION.eq(expectedVersion))
                .and(AGENT_RUN.NEXT_STEP_INDEX.eq(expectedStepIndex))
                .and(ownedProject(ownerId))
                .execute() == 1;
    }

    /**
     * 原 SQL 用 {@code update agent_run ar ... from project p} 联结所有者。
     * jOOQ DSL 没有 UPDATE ... FROM；由于 {@code p.id = ar.project_id} 是主键等值，
     * 相关存在性判断与原来的联结语义一一对应（匹配 0 行即不更新）。
     */
    private static Condition ownedProject(UUID ownerId) {
        return DSL.exists(DSL.selectOne()
                .from(PROJECT)
                .where(PROJECT.ID.eq(AGENT_RUN.PROJECT_ID))
                .and(PROJECT.OWNER_ID.eq(ownerId)));
    }

    /** 将 Run 行还原为领域 Run，包括快照和完成时间。 */
    private AgentRun map(AgentRunRecord row) {
        return new AgentRun(
                row.getId(),
                row.getProjectId(),
                row.getAgentInstanceId(),
                row.getConversationId(),
                row.getConversationTurn(),
                row.getUserId(),
                AgentRun.Status.valueOf(row.getStatus()),
                row.getInstruction(),
                objectMapper.readTree(row.getContextSnapshotJson().data()),
                objectMapper.readTree(row.getPolicySnapshotJson().data()),
                row.getProfileVersion(),
                row.getNextStepIndex(),
                row.getVersion(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant(),
                Optional.ofNullable(row.getCompletedAt())
                        .map(OffsetDateTime::toInstant)
                        .orElse(null));
    }

    /** 将绝对时刻绑定为 timestamptz 列使用的 UTC offset datetime。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
