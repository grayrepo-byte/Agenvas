package dev.agenvas.agent.infrastructure;

import static dev.agenvas.db.Tables.AGENT_INSTANCE;
import static dev.agenvas.db.Tables.PROJECT;
import static dev.agenvas.db.Tables.IDEMPOTENCY_RECORD;

import dev.agenvas.agent.application.AgentInstanceRepository;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.db.tables.records.AgentInstanceRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** PostgreSQL AgentInstance 仓储；每次查询显式传入所有者与项目，不在单例中保存请求身份。 */
@Repository
public class JooqAgentInstanceRepository implements AgentInstanceRepository {

    /** 执行 助手配置的类型化 SQL。 */
    private final DSLContext dsl;

    /** 注入 Agent 仓储使用的 jOOQ 上下文。 */
    public JooqAgentInstanceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override public boolean reserveCreateKey(UUID ownerId, String scope, String key, String hash, Instant now, Instant expiresAt) {
        return dsl.insertInto(IDEMPOTENCY_RECORD).set(IDEMPOTENCY_RECORD.PRINCIPAL_ID, ownerId)
                .set(IDEMPOTENCY_RECORD.SCOPE, scope).set(IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY, key)
                .set(IDEMPOTENCY_RECORD.REQUEST_HASH, hash).set(IDEMPOTENCY_RECORD.STATE, dev.agenvas.shared.idempotency.IdempotencyState.IN_PROGRESS.name())
                .set(IDEMPOTENCY_RECORD.CREATED_AT, atUtc(now)).set(IDEMPOTENCY_RECORD.UPDATED_AT, atUtc(now))
                .set(IDEMPOTENCY_RECORD.EXPIRES_AT, atUtc(expiresAt))
                .onConflict(IDEMPOTENCY_RECORD.PRINCIPAL_ID, IDEMPOTENCY_RECORD.SCOPE, IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY)
                .doNothing().execute() == 1;
    }

    @Override public Optional<CreateKey> findCreateKey(UUID ownerId, String scope, String key) {
        return dsl.select(IDEMPOTENCY_RECORD.REQUEST_HASH, IDEMPOTENCY_RECORD.RESOURCE_ID, IDEMPOTENCY_RECORD.RESPONSE_JSON)
                .from(IDEMPOTENCY_RECORD).where(IDEMPOTENCY_RECORD.PRINCIPAL_ID.eq(ownerId))
                .and(IDEMPOTENCY_RECORD.SCOPE.eq(scope)).and(IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY.eq(key))
                .and(IDEMPOTENCY_RECORD.STATE.eq(dev.agenvas.shared.idempotency.IdempotencyState.COMPLETED.name()))
                .fetchOptional(row -> new CreateKey(row.value1(), row.value2(), row.value3().data()));
    }

    @Override public boolean completeCreateKey(UUID ownerId, String scope, String key, String hash, UUID agentId, String response, Instant now) {
        return dsl.update(IDEMPOTENCY_RECORD).set(IDEMPOTENCY_RECORD.RESOURCE_ID, agentId)
                .set(IDEMPOTENCY_RECORD.RESPONSE_JSON, org.jooq.JSONB.valueOf(response))
                .set(IDEMPOTENCY_RECORD.STATE, dev.agenvas.shared.idempotency.IdempotencyState.COMPLETED.name())
                .set(IDEMPOTENCY_RECORD.UPDATED_AT, atUtc(now)).where(IDEMPOTENCY_RECORD.PRINCIPAL_ID.eq(ownerId))
                .and(IDEMPOTENCY_RECORD.SCOPE.eq(scope)).and(IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY.eq(key))
                .and(IDEMPOTENCY_RECORD.REQUEST_HASH.eq(hash))
                .and(IDEMPOTENCY_RECORD.STATE.eq(dev.agenvas.shared.idempotency.IdempotencyState.IN_PROGRESS.name())).execute() == 1;
    }

    /** 插入 Agent 配置行。 */
    @Override
    public void create(AgentInstance instance) {
        dsl.insertInto(AGENT_INSTANCE)
                .set(AGENT_INSTANCE.ID, instance.id())
                .set(AGENT_INSTANCE.PROJECT_ID, instance.projectId())
                .set(AGENT_INSTANCE.PROFILE_KEY, instance.profileKey())
                .set(AGENT_INSTANCE.PROFILE_VERSION, instance.profileVersion())
                .set(AGENT_INSTANCE.NAME, instance.name())
                .set(AGENT_INSTANCE.INSTRUCTION, instance.instruction())
                .set(AGENT_INSTANCE.VERSION, instance.version())
                .set(AGENT_INSTANCE.CREATED_AT, atUtc(instance.createdAt()))
                .set(AGENT_INSTANCE.UPDATED_AT, atUtc(instance.updatedAt()))
                .execute();
    }

    /** 按创建顺序列出项目 Agent。 */
    @Override
    public List<AgentInstance> list(UUID ownerId, UUID projectId) {
        return dsl.select(AGENT_INSTANCE.fields())
                .from(AGENT_INSTANCE)
                .join(PROJECT).on(PROJECT.ID.eq(AGENT_INSTANCE.PROJECT_ID))
                .where(AGENT_INSTANCE.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .orderBy(AGENT_INSTANCE.CREATED_AT, AGENT_INSTANCE.ID)
                .fetch(row -> map(row.into(AGENT_INSTANCE)));
    }

    /** 按项目和所有者读取 Agent 配置，不对配置行加锁。 */
    @Override
    public Optional<AgentInstance> find(UUID ownerId, UUID projectId, UUID agentId) {
        return find(ownerId, projectId, agentId, false);
    }

    /** 锁定 Agent 配置行，供配置版本检查更新事务使用。 */
    @Override
    public Optional<AgentInstance> findForUpdate(
            UUID ownerId, UUID projectId, UUID agentId) {
        return find(ownerId, projectId, agentId, true);
    }

    /** 普通读取与加锁读取共用项目所有者约束逻辑。 */
    private Optional<AgentInstance> find(
            UUID ownerId, UUID projectId, UUID agentId, boolean forUpdate) {
        var scoped = dsl.select(AGENT_INSTANCE.fields())
                .from(AGENT_INSTANCE)
                .join(PROJECT).on(PROJECT.ID.eq(AGENT_INSTANCE.PROJECT_ID))
                .where(AGENT_INSTANCE.ID.eq(agentId))
                .and(AGENT_INSTANCE.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId));
        // 对应原实现的 "for update of ai"：只锁定 Agent 配置行，不锁项目行。
        var selected = forUpdate ? scoped.forUpdate().of(AGENT_INSTANCE) : scoped;
        return selected
                .fetchOptional(row -> map(row.into(AGENT_INSTANCE)));
    }

    /** 以预期配置版本更新名称和指令；过期编辑返回 false。 */
    @Override
    public boolean update(
            UUID ownerId,
            AgentInstance instance,
            long expectedVersion,
            Instant updatedAt) {
        return dsl.update(AGENT_INSTANCE)
                .set(AGENT_INSTANCE.NAME, instance.name())
                .set(AGENT_INSTANCE.INSTRUCTION, instance.instruction())
                .set(AGENT_INSTANCE.VERSION, AGENT_INSTANCE.VERSION.plus(1))
                .set(AGENT_INSTANCE.UPDATED_AT, atUtc(updatedAt))
                .where(AGENT_INSTANCE.ID.eq(instance.id()))
                .and(AGENT_INSTANCE.PROJECT_ID.eq(instance.projectId()))
                .and(AGENT_INSTANCE.VERSION.eq(expectedVersion))
                // 原实现用 "from project p where p.id = ai.project_id and p.owner_id = ..." 做所有者校验；
                // project.id 是主键，因此至多匹配一行，等价于相关 EXISTS。
                .and(DSL.exists(DSL.selectOne()
                        .from(PROJECT)
                        .where(PROJECT.ID.eq(AGENT_INSTANCE.PROJECT_ID))
                        .and(PROJECT.OWNER_ID.eq(ownerId))))
                .execute() == 1;
    }

    /** 还原 Agent 配置主行。 */
    private static AgentInstance map(AgentInstanceRecord row) {
        return new AgentInstance(
                row.getId(),
                row.getProjectId(),
                row.getProfileKey(),
                row.getProfileVersion(),
                row.getName(),
                row.getInstruction(),
                row.getVersion(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    /** 将 Instant 转成 PostgreSQL timestamptz 参数所需的 UTC 时间。 */
    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
