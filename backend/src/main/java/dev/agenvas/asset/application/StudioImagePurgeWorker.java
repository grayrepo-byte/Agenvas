package dev.agenvas.asset.application;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.storage.AssetStorage;
import dev.agenvas.shared.lifecycle.BoundedWorkerExecutor;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes committed Studio results without holding a database transaction across storage I/O. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode", havingValue = "false", matchIfMissing = true)
public class StudioImagePurgeWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(StudioImagePurgeWorker.class);
    private static final int BATCH_SIZE = 16;
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private final AssetRepository assets;
    private final AssetStorage storage;
    private final Clock clock;
    private final boolean scheduled;
    private final BoundedWorkerExecutor executor;

    public StudioImagePurgeWorker(AssetRepository assets,
            @Qualifier("configuredAssetStorage") AssetStorage storage, Clock clock, ShutdownGate shutdown,
            @Value("${agenvas.asset.studio-purge-worker-enabled:true}") boolean scheduled) {
        this.assets = assets;
        this.storage = storage;
        this.clock = clock;
        this.scheduled = scheduled;
        this.executor = new BoundedWorkerExecutor(shutdown, "studio-image-purge", LOGGER,
                "Studio image purge scan failed: {}");
    }

    @Scheduled(fixedDelay = 5_000)
    public void tick() {
        if (scheduled)
            executor.execute(this::purgeDueImages);
    }

    public void purgeDueImages() {
        for (Asset asset : assets.findDueStudioImagePurges(clock.instant(), BATCH_SIZE)) {
            UUID projectId = asset.projectId();
            UUID assetId = asset.id();
            try {
                storage.withTaskImageLock(projectId, assetId, () -> {
                    // Another worker may have completed or deferred this row while we waited for the lock.
                    if (!assets.isStudioImagePurgeDue(projectId, assetId, clock.instant()))
                        return null;
                    String original = asset.objectKey();
                    String thumbnail = asset.thumbnailKey();
                    if (!storage.belongsToProject(original, projectId)
                            || (thumbnail != null && !storage.belongsToProject(thumbnail, projectId)))
                        throw new IllegalStateException("Studio image purge partition mismatch");
                    storage.discard(original);
                    if (thumbnail != null)
                        storage.discard(thumbnail);
                    assets.markStudioImagePurged(projectId, assetId, clock.instant());
                    return null;
                });
            } catch (RuntimeException failure) {
                LOGGER.warn("Studio image purge failed for {}; will retry ({})", assetId,
                        failure.getClass().getSimpleName());
                var now = clock.instant();
                assets.delayStudioImagePurge(projectId, assetId, now, now.plus(RETRY_DELAY));
            }
        }
    }

    @PreDestroy
    public void close() {
        executor.close();
    }
}
