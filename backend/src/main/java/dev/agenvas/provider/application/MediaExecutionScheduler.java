package dev.agenvas.provider.application;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
    private static final int MAX_SUBMIT_WORKERS = 3;
    private static final int MAX_POLL_WORKERS = 1;
    private static final int SHUTDOWN_WAIT_SECONDS = 10;
    private static final long INITIAL_DELAY_MS = 1_000;
    private static final long TICK_DELAY_MS = 5_000;
    private final MediaExecutionWorker worker;
    private final LegacyMediaImportService importer;
    private final ExecutorService executor = Executors.newFixedThreadPool(
            MAX_SUBMIT_WORKERS + MAX_POLL_WORKERS,
            Thread.ofPlatform().name("media-execution-", 0).factory());
    private final Semaphore submitSlots = new Semaphore(MAX_SUBMIT_WORKERS);
    private final Semaphore pollSlots = new Semaphore(MAX_POLL_WORKERS);
    private final String submitterId = "media-submit-" + UUID.randomUUID();
    private final String pollerId = "media-poll-" + UUID.randomUUID();
    private volatile boolean closed;

    public MediaExecutionScheduler(MediaExecutionWorker worker, LegacyMediaImportService importer) {
        this.worker = worker;
        this.importer = importer;
    }

    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = TICK_DELAY_MS)
    public void tick() {
        if (closed || !importer.ready()) return;
        dispatch(pollSlots, () -> worker.pollOnce(pollerId));
        for (int index = 0; index < MAX_SUBMIT_WORKERS; index++) {
            dispatch(submitSlots, () -> worker.submitOnce(submitterId));
        }
    }

    private void dispatch(Semaphore slots, Runnable operation) {
        if (!slots.tryAcquire()) return;
        try {
            executor.execute(() -> {
                try {
                    operation.run();
                } catch (RuntimeException failure) {
                    LOGGER.error("Media execution pass failed: {}",
                            failure.getClass().getSimpleName());
                } finally {
                    slots.release();
                }
            });
        } catch (RejectedExecutionException stopped) {
            slots.release();
        }
    }

    @PreDestroy
    public void close() {
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
