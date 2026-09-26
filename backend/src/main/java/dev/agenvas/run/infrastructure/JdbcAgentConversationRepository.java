package dev.agenvas.run.infrastructure;

import dev.agenvas.run.application.AgentConversationRepository;
import dev.agenvas.run.domain.AgentConversation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 会话查询始终重新核验项目所有者及 Agent 归属。 */
@Repository
public class JdbcAgentConversationRepository implements AgentConversationRepository {
    private static final RowMapper<AgentConversation> MAPPER = (rs, row) -> new AgentConversation(
            rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
            rs.getObject("agent_instance_id", UUID.class), rs.getString("title"),
            rs.getLong("version"), rs.getLong("turn_count"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    private final JdbcClient jdbc;

    public JdbcAgentConversationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<AgentConversation> find(UUID ownerId, UUID projectId, UUID agentId, UUID id) {
        return jdbc.sql("""
                select c.* from agent_conversation c join project p on p.id = c.project_id
                where p.owner_id = :ownerId and c.project_id = :projectId
                  and c.agent_instance_id = :agentId and c.id = :id
                """).param("ownerId", ownerId).param("projectId", projectId)
                .param("agentId", agentId).param("id", id).query(MAPPER).optional();
    }

    @Override
    public Optional<AgentConversation> current(UUID ownerId, UUID projectId, UUID agentId) {
        return jdbc.sql("""
                select c.* from agent_conversation c join project p on p.id = c.project_id
                join agent_instance a on a.project_id = c.project_id and a.id = c.agent_instance_id
                  and a.current_conversation_id = c.id
                where p.owner_id = :ownerId and c.project_id = :projectId and a.id = :agentId
                """).param("ownerId", ownerId).param("projectId", projectId)
                .param("agentId", agentId).query(MAPPER).optional();
    }

    @Override
    public List<AgentConversation> list(UUID ownerId, UUID projectId, UUID agentId,
            Instant beforeUpdatedAt, UUID beforeId, int limit) {
        String cursor = beforeUpdatedAt == null ? ""
                : " and (c.updated_at, c.id) < (:beforeUpdatedAt, :beforeId)";
        var query = jdbc.sql("""
                select c.* from agent_conversation c join project p on p.id = c.project_id
                where p.owner_id = :ownerId and c.project_id = :projectId
                  and c.agent_instance_id = :agentId
                """ + cursor + " order by c.updated_at desc, c.id desc limit :limit")
                .param("ownerId", ownerId).param("projectId", projectId)
                .param("agentId", agentId).param("limit", limit);
        if (beforeUpdatedAt != null) query = query.param("beforeUpdatedAt", utc(beforeUpdatedAt))
                .param("beforeId", beforeId);
        return query.query(MAPPER).list();
    }

    @Override
    public void create(AgentConversation conversation) {
        jdbc.sql("""
                insert into agent_conversation (id, project_id, agent_instance_id, title,
                    version, turn_count, created_at, updated_at)
                values (:id, :projectId, :agentId, :title, :version, :turnCount, :now, :now)
                """).param("id", conversation.id()).param("projectId", conversation.projectId())
                .param("agentId", conversation.agentInstanceId()).param("title", conversation.title())
                .param("version", conversation.version()).param("turnCount", conversation.turnCount())
                .param("now", utc(conversation.createdAt())).update();
    }

    @Override
    public boolean select(UUID ownerId, UUID projectId, UUID agentId, UUID conversationId) {
        return jdbc.sql("""
                update agent_instance a set current_conversation_id = :conversationId
                from project p, agent_conversation c
                where a.project_id = :projectId and a.id = :agentId and p.id = a.project_id
                  and p.owner_id = :ownerId and c.id = :conversationId
                  and c.project_id = a.project_id and c.agent_instance_id = a.id
                """).param("ownerId", ownerId).param("projectId", projectId)
                .param("agentId", agentId).param("conversationId", conversationId).update() == 1;
    }

    @Override
    public boolean appendTurn(UUID projectId, UUID conversationId, long expectedVersion,
            String firstTitle, Instant now) {
        return jdbc.sql("""
                update agent_conversation set turn_count = turn_count + 1, version = version + 1,
                    title = case when turn_count = 0 then :firstTitle else title end, updated_at = :now
                where project_id = :projectId and id = :id and version = :expectedVersion
                """).param("projectId", projectId).param("id", conversationId)
                .param("expectedVersion", expectedVersion).param("firstTitle", firstTitle)
                .param("now", utc(now)).update() == 1;
    }

    private OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
