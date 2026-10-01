package dev.agenvas.audit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.agenvas.audit.application.CallLogRepository;
import dev.agenvas.audit.application.CallLogRetentionService;
import dev.agenvas.audit.domain.CallLogRetentionSettings;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CallLogRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private final CallLogRepository repository = mock(CallLogRepository.class);
    private final CallLogRetentionService service = new CallLogRetentionService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    @Test void validatesBeforeWritingAndUsesCas() {
        for (int days : new int[]{0, -1, 3651}) assertThatThrownBy(() -> service.update(days, 1)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.update(null, 0)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.cleanExpired(0)).isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(repository);
        for (Integer days : new Integer[]{null, 1, 30, 90, 3650}) {
            var expected = new CallLogRetentionSettings(days, 2);
            when(repository.updateRetentionSettings(days, 1)).thenReturn(Optional.of(expected));
            assertThat(service.update(days, 1)).isEqualTo(expected);
        }
        when(repository.updateRetentionSettings(30, 2)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(30, 2)).isInstanceOf(ApiProblemException.class);
    }
    @Test void usesClockAndBoundsEveryManualRequest() {
        int size = CallLogRetentionService.BATCH_SIZE;
        when(repository.purgeExpired(NOW, size, 1)).thenReturn(size, 2);
        assertThat(service.cleanExpired(1)).isEqualTo(new dev.agenvas.audit.domain.CallLogCleanupResult(size + 2, false));
        verify(repository, times(2)).purgeExpired(NOW, size, 1);
        reset(repository);
        when(repository.purgeExpired(NOW, size, 1)).thenReturn(size);
        assertThat(service.cleanExpired(1)).isEqualTo(new dev.agenvas.audit.domain.CallLogCleanupResult(size * CallLogRetentionService.MAX_BATCHES, true));
        verify(repository, times(CallLogRetentionService.MAX_BATCHES)).purgeExpired(NOW, size, 1);
    }
    @Test void stopsWhenALaterBatchFailsWithoutRetrying() {
        when(repository.purgeExpired(any(), anyInt(), anyInt())).thenReturn(CallLogRetentionService.BATCH_SIZE)
                .thenThrow(new IllegalStateException("cleanup failed"));
        assertThatThrownBy(() -> service.cleanExpired(1)).isInstanceOf(IllegalStateException.class);
        verify(repository, times(2)).purgeExpired(NOW, CallLogRetentionService.BATCH_SIZE, 1);
    }
}
