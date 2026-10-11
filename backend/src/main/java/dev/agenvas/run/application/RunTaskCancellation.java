package dev.agenvas.run.application;

import java.time.Instant;
import java.util.UUID;

/** 由任务持久化边界实现的 Run 取消检查点。 */
public interface RunTaskCancellation {

    /**
     * READY 任务立即终结，其余未终态任务设置取消标记；审批与用量释放由 Run 事件处理。
     *
     * @param projectId 项目范围
     * @param runId 要停止后续编排的 Run
     * @param now 状态更新时间
     */
    void requestCancellation(UUID projectId, UUID runId, Instant now);
}
