package dev.agenvas.export.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The scheduled entry point uses the exact 24-hour retention and bounded cleanup batch. */
class ExportScratchJanitorTest {

    @Test
    void usesTwentyFourHourCutoffAndBoundedBatch() {
        LocalAssetStorage storage = mock(LocalAssetStorage.class);
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        new ExportScratchJanitor(storage, Clock.fixed(now, ZoneOffset.UTC)).tick();
        verify(storage).cleanupStaleExportWorkDirectories(now.minus(Duration.ofHours(24)), 100);
    }
}
