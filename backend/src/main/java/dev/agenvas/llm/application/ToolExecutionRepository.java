package dev.agenvas.llm.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Run、步骤与工具调用的持久化幂等边界。 */
public interface ToolExecutionRepository {

    /** 仅投影已提交动作的白名单字段；不读取完整工具结果或模型回合。 */
    List<RunAction> listCompletedActions(UUID projectId, UUID runId, int limit);

    /** Returns committed main-file activation and attachment read metadata for frozen approval provenance. */
    List<JsonNode> skillReads(UUID projectId, UUID runId);

    /** Server-authored receipts for asynchronous local tools; old queue-only receipts are excluded. */
    List<ToolExecution> localTaskExecutions(UUID projectId, UUID runId);

    /** A task ID alone grants no access: the current Run must own its committed local-tool receipt. */
    boolean ownsLocalTask(UUID projectId, UUID runId, UUID taskId);

    /** Append one immutable terminal projection, preserving the original acceptance receipt and completion time. */
    boolean appendLocalTaskOutcome(UUID id, JsonNode outcome);

    /** 仅在项目作用域内读取精确工具调用。 */
    Optional<ToolExecution> find(UUID projectId, UUID runId, int stepIndex, String toolCallId);

    /** 在业务变更事务中一并预留唯一工具调用。 */
    boolean insertExecuting(UUID id, UUID projectId, UUID runId, int stepIndex,
            String toolCallId, String toolName, String argumentHash, Instant now);

    /** 结果只写入一次；业务事务回滚时工具记录一并回滚。 */
    boolean complete(UUID id, JsonNode result, Instant now);
}
