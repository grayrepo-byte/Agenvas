package dev.agenvas.llm.application;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 按原始模型 tool_call_id 去重的工具执行账本。
 *
 * @param id 本次工具执行的业务操作 ID
 * @param projectId 工具作用的项目边界
 * @param runId 发出工具调用的 Run
 * @param stepIndex 发出工具调用的模型回合序号
 * @param toolCallId 模型响应中保存的原始调用 ID，参与幂等键
 * @param toolName 与已保存响应核对的工具名称
 * @param argumentHash 原始参数字符串的 SHA-256 摘要，防止同 ID 换载荷
 * @param status 执行中或已完成
 * @param result 已提交结果；EXECUTING 时为空
 */
public record ToolExecution(UUID id, UUID projectId, UUID runId, int stepIndex,
        String toolCallId, String toolName, String argumentHash, Status status,
        JsonNode result) {

    /** 工具账本状态；开始与完成在同一业务事务中。 */
    public enum Status {
        /** 事务内预留调用 ID；不应作为未完成记录独立提交。 */
        EXECUTING,
        /** 业务副作用与结构化结果已一起提交，可供重放。 */
        COMPLETED
    }
}
