package dev.agenvas.run.domain;

import java.time.Instant;
import java.util.UUID;

/** 同一 Agent 的持久会话；每条消息仍由独立 Run 执行，版本只随已受理消息递增。 */
public record AgentConversation(UUID id, UUID projectId, UUID agentInstanceId, String title,
        long version, long turnCount, Instant createdAt, Instant updatedAt) {}
