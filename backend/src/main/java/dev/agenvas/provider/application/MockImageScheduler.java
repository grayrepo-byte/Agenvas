package dev.agenvas.provider.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls durable approved image Tasks without requiring the browser connection to stay open. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnBean(MockImageWorker.class)
@ConditionalOnProperty(prefix = "agenvas.provider.mock", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class MockImageScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MockImageScheduler.class);
    private final MockImageWorker worker;
    private final String workerId = "mock-image-" + UUID.randomUUID();

    public MockImageScheduler(MockImageWorker worker) {
        this.worker = worker;
    }

    /** A bounded batch is retried on the next tick; ambiguous submissions recover as UNKNOWN. */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        try {
            worker.runOnce(workerId);
        } catch (RuntimeException failure) {
            LOGGER.error("Mock image scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
