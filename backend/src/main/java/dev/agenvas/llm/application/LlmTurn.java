package dev.agenvas.llm.application;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 一个持久化模型回合。完整响应落库后才允许执行其中的工具调用。
 *
 * @param projectId 回合所属项目
 * @param runId 回合所属 Run
 * @param stepIndex Run 内从零开始的回合序号，参与检查点唯一键
 * @param status 请求已保存或完整响应已保存
 * @param modelConfigVersion 本次回合固定的模型配置版本，恢复时不得切换
 * @param request 模型实际可见的消息与工具定义快照
 * @param response 包含全部 generation 和工具调用 ID 的完整响应；REQUESTED 时为空
 * @param createdAt 请求检查点创建时间
 * @param respondedAt 完整响应提交时间；REQUESTED 时为空
 */
public record LlmTurn(UUID projectId, UUID runId, int stepIndex, Status status,
        int modelConfigVersion, JsonNode request, JsonNode response,
        Instant createdAt, Instant respondedAt) {

    /** 模型请求与响应的持久化检查点状态。 */
    public enum Status {
        /** 请求已保存但响应未落库；崩溃后可能再次调用模型。 */
        REQUESTED,
        /** 完整响应已落库，恢复时禁止再次调用模型。 */
        RESPONDED
    }
}
