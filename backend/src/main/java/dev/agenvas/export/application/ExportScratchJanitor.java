package dev.agenvas.export.application;

import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reclaims only old, unlocked FFmpeg workspaces left by a stopped export process. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas.export", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class ExportScratchJanitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExportScratchJanitor.class);
    private static final Duration RETENTION = Duration.ofHours(24);
    private final LocalAssetStorage storage;
    private final Clock clock;

    public ExportScratchJanitor(LocalAssetStorage storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    /** A bounded hourly pass never touches active OS-locked directories. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void tick() {
        try {
            int removed = storage.cleanupStaleExportWorkDirectories(
                    clock.instant().minus(RETENTION), 100);
            if (removed > 0) LOGGER.info("Removed {} stale export workspaces", removed);
        } catch (RuntimeException failure) {
            LOGGER.error("Export scratch cleanup failed: {}", failure.getClass().getSimpleName());
        }
    }
}
