package dev.agenvas.run.application;

import dev.agenvas.run.domain.AgentConversation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 持久会话及当前选择；写入方须先持有项目事件行锁。 */
public interface AgentConversationRepository {
    Optional<AgentConversation> find(UUID ownerId, UUID projectId, UUID agentId, UUID id);
    Optional<AgentConversation> current(UUID ownerId, UUID projectId, UUID agentId);
    List<AgentConversation> list(UUID ownerId, UUID projectId, UUID agentId,
            Instant beforeUpdatedAt, UUID beforeId, int limit);
    void create(AgentConversation conversation);
    boolean select(UUID ownerId, UUID projectId, UUID agentId, UUID conversationId);
    boolean appendTurn(UUID projectId, UUID conversationId, long expectedVersion,
            String firstTitle, Instant now);
}
