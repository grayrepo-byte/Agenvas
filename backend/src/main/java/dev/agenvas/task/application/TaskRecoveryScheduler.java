package dev.agenvas.task.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically classifies expired external submissions without reissuing them. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
public class TaskRecoveryScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskRecoveryScheduler.class);
    private final TaskService tasks;
    private final TaskProperties properties;

    public TaskRecoveryScheduler(TaskService tasks, TaskProperties properties) {
        this.tasks = tasks;
        this.properties = properties;
    }

    /** One bounded scan; failures are logged and retried by the next scheduled pass. */
    @Scheduled(fixedDelay = 5_000)
    public void scan() {
        try {
            int recovered = tasks.recoverExpiredSubmissions(properties.maxClaimBatch());
            int canceled = tasks.recoverExpiredCancellations(properties.maxClaimBatch());
            if (recovered > 0) {
                LOGGER.warn("Classified {} expired provider submissions as UNKNOWN", recovered);
            }
            if (canceled > 0) {
                LOGGER.info("Closed {} expired canceled local task leases", canceled);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Provider submission recovery scan failed", failure);
        }
    }
}
