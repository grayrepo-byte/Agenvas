package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MediaExecutionSchedulerTest {
    @Test
    void dispatchesThreeSubmissionsConcurrentlyWithoutBuildingAnUnboundedBacklog()
            throws Exception {
        MediaExecutionWorker worker = mock(MediaExecutionWorker.class);
        LegacyMediaImportService importer = mock(LegacyMediaImportService.class);
        when(importer.ready()).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 1;
        }).when(worker).submitOnce(anyString());
        MediaExecutionScheduler scheduler = new MediaExecutionScheduler(worker, importer);
        try {
            scheduler.tick();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            scheduler.tick();
            verify(worker, times(3)).submitOnce(anyString());
        } finally {
            release.countDown();
            scheduler.close();
        }
    }
}
