package dev.agenvas.run.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import dev.agenvas.task.domain.Task;

/** 由任务持久化边界实现的 Run 取消检查点。 */
public interface RunTaskCancellation {

    /**
     * 立即取消未提交任务，对执行中任务设置取消标记，并只返回本次立即取消的媒体任务。
     *
     * @param projectId 项目范围
     * @param runId 要停止后续编排的 Run
     * @param now 状态更新时间
     * @return 可安全释放用量预留的已取消未提交媒体任务
     */
    List<Task> requestCancellation(UUID projectId, UUID runId, Instant now);
}
