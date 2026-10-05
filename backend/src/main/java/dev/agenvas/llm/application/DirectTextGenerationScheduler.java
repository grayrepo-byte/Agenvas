package dev.agenvas.llm.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls persisted direct text work only when the configured LLM scheduler is enabled. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas.llm", name = "scheduler-enabled", havingValue = "true")
public class DirectTextGenerationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectTextGenerationScheduler.class);
    private final DirectTextGenerationWorker worker;
    private final String workerId = "direct-text-" + UUID.randomUUID();

    public DirectTextGenerationScheduler(DirectTextGenerationWorker worker) {
        this.worker = worker;
    }

    @Scheduled(initialDelay = 1_500, fixedDelay = 5_000)
    public void tick() {
        try {
            worker.runOnce(workerId);
        } catch (RuntimeException failure) {
            LOGGER.error("Direct-text scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
