package dev.agenvas.task.application;

import dev.agenvas.shared.lifecycle.BoundedWorkerExecutor;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期核对过期提交的归档结果和已取消的租约。 */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode", havingValue = "false", matchIfMissing = true)
public class TaskRecoveryScheduler {

    /** 记录恢复数量或本轮扫描错误，不输出任务输入。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskRecoveryScheduler.class);
    private final TaskService tasks;
    private final TaskProperties properties;
    private final BoundedWorkerExecutor executor;

    public TaskRecoveryScheduler(TaskService tasks, TaskProperties properties, ShutdownGate shutdown) {
        this.tasks = tasks;
        this.properties = properties;
        this.executor = new BoundedWorkerExecutor(shutdown, "task-recovery", LOGGER,
                "Task recovery scan failed: {}");
    }

    /** 存储核对可能等待 I/O，留在单独的有界执行线程。 */
    @Scheduled(fixedDelay = 5_000)
    public void scan() {
        executor.execute(() -> {
            int recovered = tasks.recoverExpiredSubmissions(properties.maxClaimBatch());
            int canceled = tasks.recoverExpiredCancellations(properties.maxClaimBatch());
            if (recovered > 0) {
                LOGGER.info("Processed {} expired provider submissions", recovered);
            }
            if (canceled > 0) {
                LOGGER.info("Closed {} expired canceled local task leases", canceled);
            }
        });
    }

    @PreDestroy
    public void close() {
        executor.close();
    }
}
