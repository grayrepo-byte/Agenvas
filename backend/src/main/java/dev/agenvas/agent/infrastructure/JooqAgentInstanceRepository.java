package dev.agenvas.agent.infrastructure;

import static dev.agenvas.db.Tables.AGENT_INSTANCE;
import static dev.agenvas.db.Tables.PROJECT;

import dev.agenvas.agent.application.AgentInstanceRepository;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.db.tables.records.AgentInstanceRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    /** 数据库唯一约束保证每个项目只返回一个导演。 */
    @Override
    public Optional<AgentInstance> findProjectDirector(UUID ownerId, UUID projectId) {
        return dsl.select(AGENT_INSTANCE.fields())
                .from(AGENT_INSTANCE)
                .join(PROJECT).on(PROJECT.ID.eq(AGENT_INSTANCE.PROJECT_ID))
                .where(AGENT_INSTANCE.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .fetchOptional(row -> map(row.into(AGENT_INSTANCE)));
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
