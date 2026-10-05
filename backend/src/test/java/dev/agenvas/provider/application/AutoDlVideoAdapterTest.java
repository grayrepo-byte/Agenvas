package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.AutoDlWorkflows;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.AutoDlClient;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.settings.application.CredentialCipher;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Query recovery selects one video without submitting a second external generation. */
class AutoDlVideoAdapterTest {
    private final AutoDlClient client = mock(AutoDlClient.class);
    private final JooqMediaCapabilityRepository catalog = mock(JooqMediaCapabilityRepository.class);
    private final CredentialCipher cipher = mock(CredentialCipher.class);
    private final MediaCapabilityBinding binding = new MediaCapabilityBinding(UUID.randomUUID(), 1,
            UUID.randomUUID(), 1, AutoDlWorkflows.ADAPTER_ID, "mapping-hash");
    private final AttemptContext context = new AttemptContext(null, binding, UUID.randomUUID(),
            "synthetic-request-key", "synthetic-original-task");
    private final AutoDlVideoAdapter adapter = new AutoDlVideoAdapter(catalog, cipher, null, null,
            client, new ObjectMapper(), Clock.systemUTC());
    private final MediaPayload payload = new MediaPayload(new ByteArrayInputStream(new byte[] {1}), "video/mp4");

    AutoDlVideoAdapterTest() {
        var version = new JooqMediaCapabilityRepository.ConnectionVersion(binding.connectionId(), 1,
                "https://autodl.art", "origin-hash", new byte[] {1}, new byte[] {2}, 1, "fake");
        when(catalog.snapshotAt(binding.capabilityId(), 1, binding.connectionId(), 1))
                .thenReturn(Optional.of(new JooqMediaCapabilityRepository.Snapshot(null, null, version,
                        binding.adapterId(), binding.mappingSha256(), null)));
        when(cipher.decryptMedia(eq(binding.connectionId()), eq(1), any())).thenReturn("fake-test-token");
    }

    @Test void multipleVideosSelectTheFirstVideoAndIgnoreOtherMedia() {
        when(client.query("fake-test-token", context.originalRequestId())).thenReturn(success("first", "second"));
        when(client.downloadVideo("first")).thenReturn(payload);

        assertThat(adapter.reconcile(context)).isEqualTo(new Submission.Completed(payload));
        verify(client).downloadVideo("first");
        verify(client, never()).downloadVideo("second");
        verify(client, never()).downloadVideo("image");
        verify(client, never()).create(any(), any(), any());
    }

    @Test void expiredFirstVideoRefreshesOriginalTaskAndSelectsFirstRefreshedVideo() {
        when(client.query("fake-test-token", context.originalRequestId()))
                .thenReturn(success("expired", "unused"), success("fresh", "also-unused"));
        when(client.downloadVideo("expired")).thenThrow(new AutoDlClient.ResultExpired());
        when(client.downloadVideo("fresh")).thenReturn(payload);

        assertThat(adapter.reconcile(context)).isEqualTo(new Submission.Completed(payload));
        verify(client).downloadVideo("fresh");
        verify(client, never()).downloadVideo("unused");
        verify(client, never()).downloadVideo("also-unused");
        verify(client, never()).create(any(), any(), any());
    }

    @Test void noVideoStillReportsMissingOutput() {
        when(client.query("fake-test-token", context.originalRequestId()))
                .thenReturn(new AutoDlClient.TaskState("SUCCESS", List.of(new AutoDlClient.Result("image", "image"))));
        assertThat(adapter.reconcile(context)).isEqualTo(new Submission.Blocked("AUTODL_RESULT_MISSING_VIDEO"));
        verify(client, never()).downloadVideo(any());
    }

    @Test void unchangedExpiredAddressDoesNotDownloadAnotherResultOrCreateTask() {
        when(client.query("fake-test-token", context.originalRequestId())).thenReturn(success("expired", "unused"));
        when(client.downloadVideo("expired")).thenThrow(new AutoDlClient.ResultExpired());
        assertThat(adapter.reconcile(context)).isEqualTo(new Submission.Blocked("AUTODL_RESULT_EXPIRED"));
        verify(client, never()).downloadVideo("unused");
        verify(client, never()).create(any(), any(), any());
    }

    private AutoDlClient.TaskState success(String first, String second) {
        return new AutoDlClient.TaskState("SUCCESS", List.of(new AutoDlClient.Result("image", "image"),
                new AutoDlClient.Result(first, "video"), new AutoDlClient.Result(second, "video")));
    }
}
