package dev.agenvas.task.application;

import dev.agenvas.task.domain.Task;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用任务认领时的同一数据库行和租约 epoch，阻止失去执行权的 Worker 写入 Agent 业务状态。 */
@Service
public class AgentTurnLeaseGuard {

    /** 在业务事务中锁定并核验当前任务租约。 */
    private final TaskRepository tasks;
    /** 提供核验租约是否过期时使用的可注入时间源。 */
    private final Clock clock;

    /** 注入锁定并验证租约的仓储操作及时间源。
     * @param tasks 在业务事务中锁住并验证当前租约 epoch
     * @param clock 判断租约是否过期的时间源
     */
    public AgentTurnLeaseGuard(TaskRepository tasks, Clock clock) {
        this.tasks = tasks;
        this.clock = clock;
    }

    /**
     * 在当前事务结束前持有活动租约行锁；取消、接管或过期的租约不得继续产生业务副作用。
     *
     * @param lease 已认领的 AGENT_TURN 任务快照，包含预期 lease_epoch
     * @param workerId 认领该租约的 Worker 标识
     */
    @Transactional
    public void requireActive(Task lease, String workerId) {
        if (lease == null || lease.kind() != Task.Kind.AGENT_TURN
                || workerId == null || workerId.isBlank()
                || !tasks.lockActiveAgentTurnLease(lease.projectId(), lease.runId(),
                        lease.id(), workerId, lease.leaseEpoch(), clock.instant())) {
            throw new IllegalStateException("Agent turn lease was lost or canceled");
        }
    }
}
