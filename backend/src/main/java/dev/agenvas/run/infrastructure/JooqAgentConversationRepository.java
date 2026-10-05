package dev.agenvas.run.infrastructure;

import static dev.agenvas.db.Tables.AGENT_CONVERSATION;
import static dev.agenvas.db.Tables.AGENT_INSTANCE;
import static dev.agenvas.db.Tables.PROJECT;

import dev.agenvas.db.tables.records.AgentConversationRecord;
import dev.agenvas.run.application.AgentConversationRepository;
import dev.agenvas.run.domain.AgentConversation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** 会话查询始终重新核验项目所有者及 Agent 归属。 */
@Repository
public class JooqAgentConversationRepository implements AgentConversationRepository {

    /** 执行带所有者、项目和 Agent 边界的会话查询。 */
    private final DSLContext dsl;

    /** 注入会话查询上下文。 */
    public JooqAgentConversationRepository(DSLContext dsl) { this.dsl = dsl; }

    @Override
    public Optional<AgentConversation> find(UUID ownerId, UUID projectId, UUID agentId, UUID id) {
        return dsl.select(AGENT_CONVERSATION.fields())
                .from(AGENT_CONVERSATION)
                .join(PROJECT).on(PROJECT.ID.eq(AGENT_CONVERSATION.PROJECT_ID))
                .where(PROJECT.OWNER_ID.eq(ownerId))
                .and(AGENT_CONVERSATION.PROJECT_ID.eq(projectId))
                .and(AGENT_CONVERSATION.AGENT_INSTANCE_ID.eq(agentId))
                .and(AGENT_CONVERSATION.ID.eq(id))
                .fetchOptional(record -> map(record.into(AGENT_CONVERSATION)));
    }

    @Override
    public Optional<AgentConversation> current(UUID ownerId, UUID projectId, UUID agentId) {
        return dsl.select(AGENT_CONVERSATION.fields())
                .from(AGENT_CONVERSATION)
                .join(PROJECT).on(PROJECT.ID.eq(AGENT_CONVERSATION.PROJECT_ID))
                .join(AGENT_INSTANCE).on(AGENT_INSTANCE.PROJECT_ID.eq(AGENT_CONVERSATION.PROJECT_ID)
                        .and(AGENT_INSTANCE.ID.eq(AGENT_CONVERSATION.AGENT_INSTANCE_ID))
                        .and(AGENT_INSTANCE.CURRENT_CONVERSATION_ID.eq(AGENT_CONVERSATION.ID)))
                .where(PROJECT.OWNER_ID.eq(ownerId))
                .and(AGENT_CONVERSATION.PROJECT_ID.eq(projectId))
                .and(AGENT_INSTANCE.ID.eq(agentId))
                .fetchOptional(record -> map(record.into(AGENT_CONVERSATION)));
    }

    @Override
    public List<AgentConversation> list(UUID ownerId, UUID projectId, UUID agentId,
            Instant beforeUpdatedAt, UUID beforeId, int limit) {
        Condition boundary = PROJECT.OWNER_ID.eq(ownerId)
                .and(AGENT_CONVERSATION.PROJECT_ID.eq(projectId))
                .and(AGENT_CONVERSATION.AGENT_INSTANCE_ID.eq(agentId));
        if (beforeUpdatedAt != null) {
            boundary = boundary.and(DSL.row(AGENT_CONVERSATION.UPDATED_AT, AGENT_CONVERSATION.ID)
                    .lt(utc(beforeUpdatedAt), beforeId));
        }
        return dsl.select(AGENT_CONVERSATION.fields())
                .from(AGENT_CONVERSATION)
                .join(PROJECT).on(PROJECT.ID.eq(AGENT_CONVERSATION.PROJECT_ID))
                .where(boundary)
                .orderBy(AGENT_CONVERSATION.UPDATED_AT.desc(), AGENT_CONVERSATION.ID.desc())
                .limit(limit)
                .fetch(record -> map(record.into(AGENT_CONVERSATION)));
    }

    @Override
    public void create(AgentConversation conversation) {
        dsl.insertInto(AGENT_CONVERSATION)
                .set(AGENT_CONVERSATION.ID, conversation.id())
                .set(AGENT_CONVERSATION.PROJECT_ID, conversation.projectId())
                .set(AGENT_CONVERSATION.AGENT_INSTANCE_ID, conversation.agentInstanceId())
                .set(AGENT_CONVERSATION.TITLE, conversation.title())
                .set(AGENT_CONVERSATION.VERSION, conversation.version())
                .set(AGENT_CONVERSATION.TURN_COUNT, conversation.turnCount())
                .set(AGENT_CONVERSATION.CREATED_AT, utc(conversation.createdAt()))
                .set(AGENT_CONVERSATION.UPDATED_AT, utc(conversation.createdAt()))
                .execute();
    }

    /**
     * 原 SQL 用 {@code update agent_instance a ... from project p, agent_conversation c} 联结。
     * jOOQ DSL 没有 UPDATE ... FROM；{@code p.id = a.project_id} 与 {@code c.id = :conversationId}
     * 都是主键等值，因此两个相关存在性判断与原来的交叉联结语义一一对应。
     */
    @Override
    public boolean select(UUID ownerId, UUID projectId, UUID agentId, UUID conversationId) {
        return dsl.update(AGENT_INSTANCE)
                .set(AGENT_INSTANCE.CURRENT_CONVERSATION_ID, conversationId)
                .where(AGENT_INSTANCE.PROJECT_ID.eq(projectId))
                .and(AGENT_INSTANCE.ID.eq(agentId))
                .and(DSL.exists(DSL.selectOne()
                        .from(PROJECT)
                        .where(PROJECT.ID.eq(AGENT_INSTANCE.PROJECT_ID))
                        .and(PROJECT.OWNER_ID.eq(ownerId))))
                .and(DSL.exists(DSL.selectOne()
                        .from(AGENT_CONVERSATION)
                        .where(AGENT_CONVERSATION.ID.eq(conversationId))
                        .and(AGENT_CONVERSATION.PROJECT_ID.eq(AGENT_INSTANCE.PROJECT_ID))
                        .and(AGENT_CONVERSATION.AGENT_INSTANCE_ID.eq(AGENT_INSTANCE.ID))))
                .execute() == 1;
    }

    @Override
    public boolean appendTurn(UUID projectId, UUID conversationId, long expectedVersion,
            String firstTitle, Instant now) {
        return dsl.update(AGENT_CONVERSATION)
                .set(AGENT_CONVERSATION.TURN_COUNT, AGENT_CONVERSATION.TURN_COUNT.add(1))
                .set(AGENT_CONVERSATION.VERSION, AGENT_CONVERSATION.VERSION.add(1))
                .set(AGENT_CONVERSATION.TITLE, DSL.when(AGENT_CONVERSATION.TURN_COUNT.eq(0L), firstTitle)
                        .otherwise(AGENT_CONVERSATION.TITLE))
                .set(AGENT_CONVERSATION.UPDATED_AT, utc(now))
                .where(AGENT_CONVERSATION.PROJECT_ID.eq(projectId))
                .and(AGENT_CONVERSATION.ID.eq(conversationId))
                .and(AGENT_CONVERSATION.VERSION.eq(expectedVersion))
                .execute() == 1;
    }

    /** 将会话行还原为领域会话。 */
    private AgentConversation map(AgentConversationRecord row) {
        return new AgentConversation(row.getId(), row.getProjectId(),
                row.getAgentInstanceId(), row.getTitle(),
                row.getVersion(), row.getTurnCount(),
                row.getCreatedAt().toInstant(), row.getUpdatedAt().toInstant());
    }

    private OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
