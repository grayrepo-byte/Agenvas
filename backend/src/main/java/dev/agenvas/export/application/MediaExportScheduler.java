package dev.agenvas.export.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Continues durable local exports after the requesting browser has disconnected. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas.export", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class MediaExportScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaExportScheduler.class);
    private final MediaExportWorker worker;
    private final String workerId = "media-export-" + UUID.randomUUID();

    public MediaExportScheduler(MediaExportWorker worker) {
        this.worker = worker;
    }

    /** A pass takes at most one project-level Task and never occupies an HTTP request thread. */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        try {
            worker.runOnce(workerId);
        } catch (RuntimeException failure) {
            LOGGER.error("Media export scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
