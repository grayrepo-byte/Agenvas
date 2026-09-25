package dev.agenvas.provider.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Schedules only pinned media work; provider mode is irrelevant to this route. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas.provider.media", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class MediaExecutionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaExecutionScheduler.class);
    private final MediaExecutionWorker worker;
    private final LegacyMediaImportService importer;
    private final String submitterId = "media-submit-" + UUID.randomUUID();
    private final String pollerId = "media-poll-" + UUID.randomUUID();

    public MediaExecutionScheduler(MediaExecutionWorker worker, LegacyMediaImportService importer) {
        this.worker = worker;
        this.importer = importer;
    }

    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        if (!importer.ready()) return;
        try {
            worker.pollOnce(pollerId);
            worker.submitOnce(submitterId);
        } catch (RuntimeException failure) {
            LOGGER.error("Media execution pass failed: {}", failure.getClass().getSimpleName());
        }
    }
}
