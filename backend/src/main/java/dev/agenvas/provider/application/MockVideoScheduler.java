package dev.agenvas.provider.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Advances approved Mock video Tasks independently of the browser or image scheduler. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnBean(MockVideoWorker.class)
@ConditionalOnProperty(prefix = "agenvas.provider.mock", name = "video-scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class MockVideoScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MockVideoScheduler.class);
    private final MockVideoWorker worker;
    private final String workerId = "mock-video-" + UUID.randomUUID();

    public MockVideoScheduler(MockVideoWorker worker) {
        this.worker = worker;
    }

    /** Each tick claims at most one fenced video submission. */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        try {
            worker.runOnce(workerId);
        } catch (RuntimeException failure) {
            LOGGER.error("Mock video scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
