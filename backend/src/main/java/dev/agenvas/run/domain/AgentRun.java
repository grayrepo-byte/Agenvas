package dev.agenvas.run.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 一次用户指令的持久化运行；创建时固定 Agent 上下文及策略，后续回合只推进状态与游标。
 *
 * @param id Run 身份
 * @param projectId 占用活动槽位的项目
 * @param agentInstanceId 发起运行的 Agent 卡片
 * @param conversationId 用户选择的持久会话，决定可以继承的公开历史范围
 * @param conversationTurn 在会话项目锁内分配的单调消息序号
 * @param userId 创建 Run 的服务端用户身份
 * @param status 当前编排状态
 * @param instruction 创建时固定的用户指令
 * @param contextSnapshot 创建时选定的绑定、画布选择与项目上下文
 * @param policySnapshot 模型配置版本及预算等执行策略快照
 * @param profileVersion 创建时 Agent 配置档案版本
 * @param nextStepIndex 下一个可调度的模型回合序号
 * @param version 状态和游标更新的乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 最近一次状态变化时间
 * @param completedAt 到达终态的时间；活动态为空
 */
public record AgentRun(
        UUID id,
        UUID projectId,
        UUID agentInstanceId,
        UUID conversationId,
        long conversationTurn,
        UUID userId,
        Status status,
        String instruction,
        JsonNode contextSnapshot,
        JsonNode policySnapshot,
        int profileVersion,
        int nextStepIndex,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    /** Historical policies retain their frozen budgets; null in v6 means no count limit. */
    private static final int LEGACY_MAX_TOOL_EXECUTIONS = 40;

    public boolean modelTurnLimitReached(int stepIndex) {
        return limitReached("maxModelTurns", 12, stepIndex);
    }

    /** Unbounded policies do not require an ever-growing ledger count before each tool. */
    public boolean hasToolExecutionLimit() {
        return configuredLimit("maxToolExecutions", LEGACY_MAX_TOOL_EXECUTIONS) != null;
    }

    public boolean toolExecutionLimitReached(long completedCount) {
        return limitReached("maxToolExecutions", LEGACY_MAX_TOOL_EXECUTIONS, completedCount);
    }

    private boolean limitReached(String field, int legacyDefault, long count) {
        Long value = configuredLimit(field, legacyDefault);
        return value != null && count >= value;
    }

    private Long configuredLimit(String field, int legacyDefault) {
        JsonNode limit = policySnapshot.path(field);
        if (limit.isNull() && policySnapshot.path("schemaVersion").asInt() >= 6) return null;
        long value = limit.isMissingNode() ? legacyDefault : limit.isIntegralNumber() ? limit.longValue() : -1;
        if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalStateException("Invalid frozen Run budget");
        return value;
    }

    /** 持久化运行状态；等待任务和阻断均未释放项目活动槽位。 */
    public enum Status {
        /** 已创建首个模型任务，等待 Worker 认领。 */
        QUEUED,
        /** 模型或工具回合正在推进。 */
        RUNNING,
        /** 同 Run 的媒体任务执行中，等待结果后恢复编排。 */
        WAITING_TASKS,
        /** 存在未决事项，仍保留运行上下文与项目槽位。 */
        BLOCKED,
        /** 取消意图已落库，正在停止后续任务编排。 */
        CANCEL_REQUESTED,
        /** 本系统后续编排已停止；外部请求可能仍在执行。 */
        CANCELED,
        /** Run 已以失败结束，释放槽位并保留已有产物。 */
        FAILED,
        /** Run 已按当前目标成功结束。 */
        SUCCEEDED;

        /** 释放项目活动槽位的最终状态集合；BLOCKED 和等待状态不在其中。 */
        private static final Set<Status> TERMINAL = Set.of(CANCELED, FAILED, SUCCEEDED);

        /** 仅真正终态返回 true；WAITING 和 BLOCKED 仍占用项目活动槽位。 */
        public boolean terminal() {
            return TERMINAL.contains(this);
        }
    }
}
