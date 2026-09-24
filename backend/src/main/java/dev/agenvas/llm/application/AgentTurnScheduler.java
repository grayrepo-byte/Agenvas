package dev.agenvas.llm.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅在运维启用调度器且非恢复模式时轮询数据库中的模型回合任务。 */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas.llm", name = "scheduler-enabled", havingValue = "true")
public class AgentTurnScheduler {

    /** 记录调度错误类别，不记录模型提示词或工具参数。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentTurnScheduler.class);
    /** 执行一次有界模型任务认领和处理。 */
    private final AgentTurnWorker worker;
    /** 本实例独有的租约持有者，任务接管时用于区分其他 Worker。 */
    private final String workerId = "agent-turn-" + UUID.randomUUID();

    /** 注入一次处理一个持久化 Agent 回合任务的 Worker。
     * @param worker 负责认领、调用模型并提交回合结果
     */
    /** 注入从数据库认领并处理单个 Agent 模型回合的 Worker。
     * @param worker 模型回合任务处理器
     */
    public AgentTurnScheduler(AgentTurnWorker worker) {
        this.worker = worker;
    }

    /** 每次调度最多认领一个任务；重启后由任务表恢复，不依赖内存队列。 */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        try {
            worker.runOnce(workerId);
        } catch (RuntimeException failure) {
            LOGGER.error("Agent-turn scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
