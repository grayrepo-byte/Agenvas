package dev.agenvas.mediatemplate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptHttpClient;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class ThirdPartyPromptSchedulingTest {
    private final ThirdPartyPromptRepository repository = mock(ThirdPartyPromptRepository.class);
    private final ThirdPartyPromptSyncSettingsService settings = mock(ThirdPartyPromptSyncSettingsService.class);
    private ThirdPartyPromptService service(boolean deploymentEnabled) {
        return spy(new ThirdPartyPromptService(repository, mock(ThirdPartyPromptHttpClient.class), List.of(),
                Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC),
                mock(PlatformTransactionManager.class), settings, deploymentEnabled, false));
    }

    @Test void defaultOffDoesNotEvenScanSources() {
        when(settings.get()).thenReturn(new ThirdPartyPromptSyncSettings(false, true, 0));
        var service = service(true);
        try { service.syncDue(); verify(repository, after(250).never()).due(any()); }
        finally { service.close(); }
    }

    @Test void enabledConsentAllowsDueSourceButDeploymentCanStillDisableScheduler() {
        when(settings.get()).thenReturn(new ThirdPartyPromptSyncSettings(true, true, 1));
        when(repository.due(any())).thenReturn(List.of("source"));
        var enabled = service(true);
        doReturn(new ThirdPartyPromptService.SyncResult("source", ThirdPartyPromptService.SyncState.SUCCESS, 0, 0, 0)).when(enabled).syncScheduled("source");
        try { enabled.syncDue(); verify(enabled, timeout(1000)).syncScheduled("source"); }
        finally { enabled.close(); }
        reset(repository);
        var deploymentOff = service(false);
        try { deploymentOff.syncDue(); verifyNoInteractions(repository); }
        finally { deploymentOff.close(); }
    }

    @Test void disablingWhileFirstDownloadRunsDoesNotStartNextSource() {
        AtomicBoolean consent = new AtomicBoolean(true);
        when(settings.get()).thenAnswer(ignored -> new ThirdPartyPromptSyncSettings(consent.get(), true, 1));
        when(repository.due(any())).thenReturn(List.of("first", "next"));
        var service = service(true);
        doAnswer(ignored -> { consent.set(false); return new ThirdPartyPromptService.SyncResult("first", ThirdPartyPromptService.SyncState.SUCCESS, 0, 0, 0); }).when(service).syncScheduled("first");
        try {
            service.syncDue(); verify(service, timeout(1000)).syncScheduled("first");
            verify(service, after(250).never()).syncScheduled("next");
        } finally { service.close(); }
    }
}
