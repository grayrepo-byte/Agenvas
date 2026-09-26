package dev.agenvas.run.application;

import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.idempotency.IdempotencyState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 按项目所有者限定的 Run 持久化边界，同时维护创建命令的幂等仲裁记录。 */
public interface AgentRunRepository {

    /** 任何业务写入前按用户、作用域和命令键预留幂等记录。 */
    boolean reserveIdempotency(
            UUID principalId,
            String scope,
            String key,
            String requestHash,
            Instant expiresAt,
            Instant now);

    /** 唯一约束仲裁后读取竞争中或已完成的原命令记录。 */
    Optional<IdempotencyRecord> findIdempotency(UUID principalId, String scope, String key);

    /** 与 Run 创建同事务，将预留记录标记完成并固定原响应。 */
    boolean completeIdempotency(
            UUID principalId,
            String scope,
            String key,
            String requestHash,
            UUID resourceId,
            String responseJson,
            Instant now);

    /** 项目活动槽位已检查并锁定后插入 Run。 */
    void create(AgentRun run);

    /** 仅在认证所有者及项目范围内读取指定 Run。 */
    Optional<AgentRun> find(UUID ownerId, UUID projectId, UUID runId);

    /** 以创建时间和 Run ID 的稳定键集分页读取同一 Agent 的运行历史。 */
    List<AgentRun> list(UUID ownerId, UUID projectId, UUID agentId,
            Instant beforeCreatedAt, UUID beforeId, int limit);

    /** 同一会话按单调消息序号倒序分页。 */
    List<AgentRun> listConversation(UUID ownerId, UUID projectId, UUID agentId,
            UUID conversationId, Long beforeTurn, int limit);

    /** 保留首轮与最近终态 Run，按会话序号正序，供冻结有界公开记忆。 */
    List<UUID> contextRunIds(UUID projectId, UUID conversationId, long throughTurn, int limit);
    long contextRunCount(UUID projectId, UUID conversationId, long throughTurn);

    /** 状态转换前在所有者和项目边界内锁定 Run。 */
    Optional<AgentRun> findForUpdate(UUID ownerId, UUID projectId, UUID runId);

    /** 仅版本匹配时更新状态；终态同时写入完成时间。 */
    boolean updateStatus(
            UUID ownerId,
            UUID projectId,
            UUID runId,
            long expectedVersion,
            AgentRun.Status status,
            Instant updatedAt,
            Instant completedAt);

    /** 比较 Run 版本和当前步骤后前移持久化模型游标，不改变状态。 */
    boolean advanceStep(UUID ownerId, UUID projectId, UUID runId,
            long expectedVersion, int expectedStepIndex, Instant updatedAt);

    /** 一次创建命令的持久化幂等仲裁记录。
     * @param requestHash 规范化命令载荷摘要；相同键不同摘要必须冲突
     * @param state 键当前处于创建中还是可重放状态
     * @param resourceId 首次命令创建的 Run UUID
     * @param responseJson 已保存的首次响应，用于同键请求一致重放
     * @param expiresAt 幂等记录的过期时刻
     */
    record IdempotencyRecord(
            String requestHash,
            IdempotencyState state,
            UUID resourceId,
            String responseJson,
            Instant expiresAt) {}
}
