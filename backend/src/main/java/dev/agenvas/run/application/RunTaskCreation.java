package dev.agenvas.run.application;

import java.time.Instant;
import java.util.UUID;

/** 在 Run 创建事务中建立首个持久化模型回合任务的边界。 */
public interface RunTaskCreation {

    /**
     * 在 Run 行和活动槽位提交前创建唯一的第零步任务。
     *
     * @param projectId Run 所属项目
     * @param runId 新建 Run
     * @param now 与 Run 创建一致的时间
     * @return 首个 AGENT_TURN 任务 ID
     */
    UUID createInitialTurn(UUID projectId, UUID runId, Instant now);
}
