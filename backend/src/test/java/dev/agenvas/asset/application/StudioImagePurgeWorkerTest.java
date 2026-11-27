package dev.agenvas.asset.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.storage.AssetStorage;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class StudioImagePurgeWorkerTest {
    @Test
    void failedThumbnailIsDeferredAndOtherAssetStillFinishes() {
        var assets = mock(AssetRepository.class);
        var storage = mock(AssetStorage.class);
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        UUID project = UUID.randomUUID();
        Asset broken = image(project, now);
        Asset healthy = image(project, now);
        when(assets.findDueStudioImagePurges(eq(now), anyInt()))
                .thenReturn(List.of(broken, healthy));
        when(assets.isStudioImagePurgeDue(eq(project), any(), eq(now))).thenReturn(true);
        when(storage.belongsToProject(any(), eq(project))).thenReturn(true);
        when(storage.withTaskImageLock(eq(project), any(), any()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
        doThrow(new IllegalStateException("synthetic storage failure"))
                .when(storage).discard(broken.thumbnailKey());

        var worker = new StudioImagePurgeWorker(assets, storage, Clock.fixed(now, ZoneOffset.UTC),
                mock(ShutdownGate.class), false);
        try {
            worker.purgeDueImages();
        } finally {
            worker.close();
        }

        var ordered = inOrder(assets, storage);
        ordered.verify(storage).discard(broken.objectKey());
        ordered.verify(assets).delayStudioImagePurge(project, broken.id(), now, now.plusSeconds(60));
        ordered.verify(storage).discard(healthy.objectKey());
        ordered.verify(storage).discard(healthy.thumbnailKey());
        ordered.verify(assets).markStudioImagePurged(project, healthy.id(), now);
        verify(assets, never()).markStudioImagePurged(project, broken.id(), now);
    }

    private static Asset image(UUID project, Instant now) {
        UUID id = UUID.randomUUID();
        String key = project + "/" + id;
        return new Asset(id, project, Asset.MediaKind.IMAGE, key + ".png", "image/png", 2, "original-hash",
                1, 1, null, key + ".thumb.png", 2L, "thumbnail-hash", now);
    }
}
