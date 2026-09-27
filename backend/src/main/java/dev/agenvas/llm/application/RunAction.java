package dev.agenvas.llm.application;

import java.time.Instant;
import java.util.UUID;

/** 已提交工具动作的公开投影；不包含模型消息、调用参数或完整工具结果。 */
public record RunAction(UUID id, int stepIndex, String toolName, Status status,
        String summary, Instant completedAt) {

    /** 工具业务结果的历史状态，不是工具账本的 COMPLETED 状态或媒体生成状态。 */
    public enum Status {
        SUCCEEDED
    }
}
