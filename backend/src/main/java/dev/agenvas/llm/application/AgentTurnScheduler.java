package dev.agenvas.llm.application;

import dev.agenvas.shared.lifecycle.BoundedWorkerExecutor;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅在运维启用调度器且非恢复模式时轮询数据库中的模型回合任务。 */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode", havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas.llm", name = "scheduler-enabled", havingValue = "true")
public class AgentTurnScheduler {

    /** 记录调度错误类别，不记录模型提示词或工具参数。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentTurnScheduler.class);
    /** 执行一次有界模型任务认领和处理。 */
    private final AgentTurnWorker worker;
    /** 本实例独有的租约持有者，任务接管时用于区分其他 Worker。 */
    private final String workerId = "agent-turn-" + UUID.randomUUID();
    private final BoundedWorkerExecutor executor;

    public AgentTurnScheduler(AgentTurnWorker worker, ShutdownGate shutdown) {
        this.worker = worker;
        this.executor = new BoundedWorkerExecutor(shutdown, "agent-turn-dispatch", LOGGER,
                "Agent-turn scheduler pass failed: {}");
    }

    /** 每次调度最多启动一次认领，模型等待留在执行线程。 */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        executor.execute(() -> worker.runOnce(workerId));
    }

    @PreDestroy
    public void close() {
        executor.close();
    }
}
