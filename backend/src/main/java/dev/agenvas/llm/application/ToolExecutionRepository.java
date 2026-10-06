package dev.agenvas.llm.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Run、步骤与工具调用的持久化幂等边界，并维护每个 Run 的工具预算。 */
public interface ToolExecutionRepository {

    /** 持有 Run 行锁时统计已完成调用，串行化新工具执行。 */
    long countByRun(UUID projectId, UUID runId);

    /** 仅投影已提交动作的白名单字段；不读取完整工具结果或模型回合。 */
    List<RunAction> listCompletedActions(UUID projectId, UUID runId, int limit);

    /** Returns committed main-file activation and attachment read metadata for frozen approval provenance. */
    List<JsonNode> skillReads(UUID projectId, UUID runId);

    /** 仅在项目作用域内读取精确工具调用。 */
    Optional<ToolExecution> find(UUID projectId, UUID runId, int stepIndex, String toolCallId);

    /** 在业务变更事务中一并预留唯一工具调用。 */
    boolean insertExecuting(UUID id, UUID projectId, UUID runId, int stepIndex,
            String toolCallId, String toolName, String argumentHash, Instant now);

    /** 结果只写入一次；业务事务回滚时工具记录一并回滚。 */
    boolean complete(UUID id, JsonNode result, Instant now);
}
