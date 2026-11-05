package dev.agenvas.mediatemplate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Format;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptHttpClient;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptHttpClient.DownloadFailure;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptHttpClient.FailureReason;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Verify only the safe classification seam; database cache/lease atomicity uses PostgreSQL integration tests. */
class ThirdPartyPromptSyncFailureTest {
    private final Instant now = Instant.parse("2026-11-04T00:00:00Z");
    private final ThirdPartyPromptRepository repository = mock(ThirdPartyPromptRepository.class);
    private final ThirdPartyPromptHttpClient http = mock(ThirdPartyPromptHttpClient.class);
    private final ThirdPartyPromptAdapter adapter = mock(ThirdPartyPromptAdapter.class);
    private final ThirdPartyPromptSyncSettingsService settings = mock(ThirdPartyPromptSyncSettingsService.class);
    private final ThirdPartyPromptSource source = new ThirdPartyPromptSource("fixture", "Synthetic source", TargetKind.IMAGE,
            Format.NATIVE_JSON, "https://example.com/feed", "", true, 0, now, now.minusSeconds(60), null, 7, false);

    @Test void recordsKnownDownloadCategoriesWithoutUpstreamDetailsOrCacheWrites() {
        for (FailureReason reason : FailureReason.values()) {
            verifyFailure(new DownloadFailure(reason, new IllegalStateException("PRIVATE URL/ERROR BODY")), "THIRD_PARTY_SOURCE_" + reason.name());
            clearInvocations(repository, settings);
        }
    }

    @Test void recordsValidationAndMalformedJsonWithoutGuessingHistoricalSourceFailure() {
        verifyFailure(ThirdPartyPromptValidation.invalid(), "THIRD_PARTY_SOURCE_INVALID_DATA");
        clearInvocations(repository, settings);
        RuntimeException malformed;
        try { new ObjectMapper().readTree("not JSON"); throw new AssertionError("Expected malformed synthetic feed"); }
        catch (RuntimeException failure) { malformed = failure; }
        verifyFailure(malformed, "THIRD_PARTY_SOURCE_INVALID_DATA");
    }

    @Test void unexpectedFailureRetainsLegacyGenericCategoryRatherThanExceptionProse() {
        verifyFailure(new IllegalStateException("PRIVATE INTERNAL DETAILS"), "THIRD_PARTY_SYNC_FAILED");
    }

    private void verifyFailure(RuntimeException failure, String code) {
        when(repository.source(source.id(), now)).thenReturn(Optional.of(source));
        when(repository.claim(eq(source.id()), any(), eq(now))).thenReturn(Optional.of(source));
        when(adapter.format()).thenReturn(Format.NATIVE_JSON);
        doThrow(failure).when(adapter).fetch(source, http);
        var service = new ThirdPartyPromptService(repository, http, List.of(adapter), Clock.fixed(now, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class), settings, true, false);
        try {
            assertThat(service.sync(source.id()).state()).isEqualTo(ThirdPartyPromptService.SyncState.FAILED);
            verify(repository).finish(eq(source.id()), any(), eq(now), eq(code));
            verify(repository, never()).upsert(any(), any(), any());
            verifyNoInteractions(settings); // Explicit manual sync does not silently enable automatic downloads.
        } finally { service.close(); }
    }
}
