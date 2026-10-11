package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition.OutputKind;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Connection;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.VideoOperation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic provider responses exercise recovery and archival boundaries without external traffic. */
class MediaExecutionWorkerTest {
    private static final String WORKER = "media-worker";

    @Test
    void restoresMixedPersistedOutputsWithoutQueryingOrGeneratingAgain() {
        Fixture fixture = new Fixture(true);
        var manifest = fixture.manifest(List.of(
                result(0, OutputKind.IMAGE, true), result(1, OutputKind.AUDIO, false),
                result(2, OutputKind.VIDEO, false)));
        when(fixture.tasks.providerResultManifest(fixture.task)).thenReturn(Optional.of(manifest));
        fixture.downloads(manifest);

        assertThat(fixture.worker.pollOnce(WORKER)).isEqualTo(1);

        verify(fixture.adapter, never()).reconcile(any());
        verify(fixture.adapter, never()).submit(any());
        verifyNoInteractions(fixture.callLogs);
        verify(fixture.tasks, never()).checkpointProviderResults(any(), anyString(), any());
        verify(fixture.assets).archiveTaskImage(eq(fixture.owner), eq(fixture.task.projectId()),
                eq(outputIdentity(fixture.task, 0)), any());
        verify(fixture.assets).archiveTaskAudio(eq(fixture.owner), eq(fixture.task.projectId()),
                eq(outputIdentity(fixture.task, 1)), any());
        verify(fixture.assets).archiveTaskVideo(eq(fixture.owner), eq(fixture.task.projectId()),
                eq(outputIdentity(fixture.task, 2)), any());
        ArgumentCaptor<List<TaskService.ArchivedProviderResult>> captured = ArgumentCaptor.captor();
        verify(fixture.tasks).succeedWithArtifacts(eq(fixture.task), eq(WORKER), captured.capture(),
                eq(manifest.usage()));
        var archived = captured.getValue();
        assertThat(archived).extracting(TaskService.ArchivedProviderResult::kind)
                .containsExactly(Artifact.Kind.IMAGE, Artifact.Kind.AUDIO, Artifact.Kind.VIDEO);
        assertThat(archived).extracting(TaskService.ArchivedProviderResult::primary)
                .containsExactly(true, false, false);
        for (int index = 0; index < archived.size(); index++) {
            var content = archived.get(index).content();
            assertThat(archived.get(index).ordinal()).isEqualTo(index);
            assertThat(content.path("sourceTaskId").asText()).isEqualTo(fixture.task.id().toString());
            assertThat(content.path("prompt").asText()).isEqualTo("user prompt");
            assertThat(content.path("parameters").path("providerOutputOrdinal").asInt()).isEqualTo(index);
            assertThat(content.path("parameters").path("providerOutputNodeId").asText()).isEqualTo("node-" + index);
            assertThat(content.path("parameters").path("providerRequestId").asText()).isEqualTo("original-request");
            assertThat(content.path("parameters").path("providerUsage")).isEqualTo(manifest.usage());
        }
        ((ObjectNode) archived.getFirst().content().path("parameters").path("composition"))
                .put("ratio", "9:16");
        assertThat(fixture.task.input().path("mediaInput").path("parameters")
                .path("composition").path("ratio").asText()).isEqualTo("16:9");
        verify(fixture.downloads).close();
    }

    @Test
    void checkpointsFreshManifestBeforeOpeningOrDownloadingResults() {
        Fixture fixture = new Fixture(true);
        var manifest = fixture.manifest(List.of(result(0, OutputKind.IMAGE, true)));
        when(fixture.adapter.reconcile(any())).thenReturn(new Submission.CompletedResults(manifest));
        fixture.downloads(manifest);

        assertThat(fixture.worker.pollOnce(WORKER)).isEqualTo(1);

        var order = inOrder(fixture.tasks, fixture.adapter, fixture.downloads);
        order.verify(fixture.tasks).checkpointProviderResults(fixture.task, WORKER, manifest);
        order.verify(fixture.adapter).openResultDownloads(any());
        order.verify(fixture.downloads).download(manifest.results().getFirst());
        order.verify(fixture.downloads).close();
        order.verify(fixture.tasks).succeedWithArtifacts(eq(fixture.task), eq(WORKER), anyList(), eq(manifest.usage()));
        verify(fixture.tasks, times(2)).providerResultManifest(fixture.task);
        ArgumentCaptor<AttemptContext> attempt = ArgumentCaptor.forClass(AttemptContext.class);
        verify(fixture.adapter).reconcile(attempt.capture());
        assertThat(attempt.getValue().originalRequestId()).isEqualTo("original-request");
        assertThat(attempt.getValue().requestKey()).isNull();
        verify(fixture.adapter, never()).submit(any());
    }

    @Test
    void payloadFinalTaskWriteFailurePropagatesAfterClosingTheResponse() {
        Fixture fixture = new Fixture(true);
        TrackingInputStream response = new TrackingInputStream(false);
        when(fixture.adapter.reconcile(any())).thenReturn(new Submission.Completed(new MediaPayload(response, "image/png")));
        RuntimeException commitFailure = new IllegalStateException("Task write failed");
        doThrow(commitFailure).when(fixture.tasks).succeedWithArtifacts(any(), anyString(), anyList(), any());

        assertThatThrownBy(() -> fixture.worker.pollOnce(WORKER)).isSameAs(commitFailure);

        assertThat(response.closed).isTrue();
        verify(fixture.tasks, never()).retryProviderPoll(any(), anyString(), anyString());
    }

    @Test
    void payloadCloseFailureRetriesPollingBeforeTheFinalTaskWrite() {
        Fixture fixture = new Fixture(true);
        TrackingInputStream response = new TrackingInputStream(true);
        when(fixture.adapter.reconcile(any())).thenReturn(new Submission.Completed(new MediaPayload(response, "image/png")));
        // AssetService has consumed the bytes successfully; only the caller's response close fails.
        doReturn(fixture.asset(fixture.task.id(), Asset.MediaKind.IMAGE)).when(fixture.assets)
                .archiveTaskImage(any(), any(), any(), any());

        assertThat(fixture.worker.pollOnce(WORKER)).isEqualTo(1);

        assertThat(response.closed).isTrue();
        verify(fixture.tasks).retryProviderPoll(fixture.task, WORKER, "PROVIDER_POLL_TECHNICAL_FAILURE");
        verify(fixture.tasks, never()).succeedWithArtifacts(any(), anyString(), anyList(), any());
    }

    @Test
    void manifestFinalTaskWriteFailureRetriesPollingAfterClosingDownloads() {
        Fixture fixture = new Fixture(true);
        var manifest = fixture.manifest(List.of(result(0, OutputKind.IMAGE, true)));
        when(fixture.tasks.providerResultManifest(fixture.task)).thenReturn(Optional.of(manifest));
        fixture.downloads(manifest);
        doThrow(new IllegalStateException("Task write failed")).when(fixture.tasks)
                .succeedWithArtifacts(any(), anyString(), anyList(), any());

        assertThat(fixture.worker.pollOnce(WORKER)).isEqualTo(1);

        verify(fixture.downloads).close();
        verify(fixture.tasks).retryProviderPoll(fixture.task, WORKER, "PROVIDER_POLL_TECHNICAL_FAILURE");
    }

    @Test
    void payloadArchiveFailureRetriesPollingAndClosesTheResponse() {
        Fixture fixture = new Fixture(true);
        TrackingInputStream response = new TrackingInputStream(false);
        when(fixture.adapter.reconcile(any())).thenReturn(new Submission.Completed(new MediaPayload(response, "image/png")));
        doThrow(new IllegalStateException("Invalid image")).when(fixture.assets)
                .archiveTaskImage(any(), any(), any(), any());

        assertThat(fixture.worker.pollOnce(WORKER)).isEqualTo(1);

        assertThat(response.closed).isTrue();
        verify(fixture.tasks).retryProviderPoll(fixture.task, WORKER, "PROVIDER_POLL_TECHNICAL_FAILURE");
        verify(fixture.tasks, never()).succeedWithArtifacts(any(), anyString(), anyList(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permanentNativeArchiveFailureBlocksPayloadAndManifestRecoveryWithoutResubmission(boolean manifestResult) {
        Fixture fixture = new Fixture(true, Task.Kind.IMAGE_GENERATION, "WAN_IMAGE");
        if (manifestResult) {
            var manifest = fixture.manifest(List.of(result(0, OutputKind.IMAGE, true)));
            when(fixture.tasks.providerResultManifest(fixture.task)).thenReturn(Optional.of(manifest));
            fixture.downloads(manifest);
        } else {
            when(fixture.adapter.reconcile(any())).thenReturn(new Submission.Completed(
                    new MediaPayload(new TrackingInputStream(false), "image/png")));
        }
        doThrow(new dev.agenvas.provider.domain.NativeMediaFailure("PROVIDER_DOWNLOAD_REJECTED"))
                .when(fixture.assets).archiveTaskImage(any(), any(), any(), any());

        assertThat(fixture.worker.pollOnce(WORKER)).isEqualTo(1);

        verify(fixture.tasks).blockProviderPoll(fixture.task, WORKER, "PROVIDER_DOWNLOAD_REJECTED");
        verify(fixture.tasks, never()).retryProviderPoll(any(), anyString(), anyString());
        verify(fixture.tasks, never()).succeedWithArtifacts(any(), anyString(), anyList(), any());
        verify(fixture.adapter, never()).submit(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredKlingResultBlocksPayloadAndManifestRecoveryWithoutResubmission(boolean manifestResult) {
        Fixture fixture = new Fixture(true, Task.Kind.IMAGE_GENERATION, "KLING_IMAGE");
        if (manifestResult) {
            var manifest = fixture.manifest(List.of(result(0, OutputKind.IMAGE, true)));
            when(fixture.tasks.providerResultManifest(fixture.task)).thenReturn(Optional.of(manifest));
            fixture.downloads(manifest);
        } else {
            when(fixture.adapter.reconcile(any())).thenReturn(new Submission.Completed(
                    new MediaPayload(new TrackingInputStream(false), "image/png")));
        }
        doThrow(new dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy.Expired())
                .when(fixture.assets).archiveTaskImage(any(), any(), any(), any());

        assertThat(fixture.worker.pollOnce(WORKER)).isEqualTo(1);

        verify(fixture.tasks).blockProviderPoll(fixture.task, WORKER, "KLING_RESULT_EXPIRED");
        verify(fixture.tasks, never()).retryProviderPoll(any(), anyString(), anyString());
        verify(fixture.tasks, never()).succeedWithArtifacts(any(), anyString(), anyList(), any());
        verify(fixture.adapter, never()).submit(any());
    }

    @Test
    void submissionArchiveFailurePropagatesWithoutTurningIntoAPollRetry() {
        Fixture fixture = new Fixture(false);
        TrackingInputStream response = new TrackingInputStream(false);
        when(fixture.adapter.submit(any())).thenReturn(new Submission.Completed(new MediaPayload(response, "image/png")));
        RuntimeException archiveFailure = new IllegalStateException("Invalid image");
        doThrow(archiveFailure).when(fixture.assets).archiveTaskImage(any(), any(), any(), any());

        assertThatThrownBy(() -> fixture.worker.submitOnce(WORKER)).isSameAs(archiveFailure);

        assertThat(response.closed).isTrue();
        verify(fixture.tasks, never()).retryProviderPoll(any(), anyString(), anyString());
        verify(fixture.tasks, never()).rejectSubmission(any(), anyString(), anyString());
        verify(fixture.adapter, never()).reconcile(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void singleResultSilentVideoFailureKeepsItsSubmissionOrPollFailureCode(boolean polling) {
        Fixture fixture = new Fixture(polling, Task.Kind.AUDIO_GENERATION);
        fixture.input.putObject(VideoOperation.SILENT_VIDEO_TARGET);
        var completed = new Submission.CompletedArtifact(fixture.mapper.createObjectNode());
        when(fixture.adapter.submit(any())).thenReturn(completed);
        when(fixture.adapter.reconcile(any())).thenReturn(completed);
        when(fixture.videoProcessor.silentVideoResult(any())).thenThrow(new IllegalStateException("Cannot process video"));

        assertThat(polling ? fixture.worker.pollOnce(WORKER) : fixture.worker.submitOnce(WORKER)).isEqualTo(1);

        if (polling) {
            verify(fixture.tasks).retryProviderPoll(fixture.task, WORKER, "LOCAL_VIDEO_PROCESSING_FAILED");
            verify(fixture.tasks, never()).rejectSubmission(any(), anyString(), anyString());
        } else {
            verify(fixture.tasks).rejectSubmission(fixture.task, WORKER, "LOCAL_VIDEO_PROCESSING_FAILED");
            verify(fixture.tasks, never()).retryProviderPoll(any(), anyString(), anyString());
        }
        verify(fixture.tasks, never()).succeedWithArtifacts(any(), anyString(), anyList(), any());
    }

    @Test
    void manifestSilentVideoFailureRemainsATechnicalPollRetry() {
        Fixture fixture = new Fixture(true, Task.Kind.AUDIO_GENERATION);
        fixture.input.putObject(VideoOperation.SILENT_VIDEO_TARGET);
        var manifest = fixture.manifest(List.of(result(0, OutputKind.AUDIO, true)));
        when(fixture.tasks.providerResultManifest(fixture.task)).thenReturn(Optional.of(manifest));
        fixture.downloads(manifest);
        when(fixture.videoProcessor.silentVideoResult(any())).thenThrow(new IllegalStateException("Cannot process video"));

        assertThat(fixture.worker.pollOnce(WORKER)).isEqualTo(1);

        verify(fixture.tasks).retryProviderPoll(fixture.task, WORKER, "PROVIDER_POLL_TECHNICAL_FAILURE");
        verify(fixture.tasks, never()).rejectSubmission(any(), anyString(), anyString());
        verify(fixture.tasks, never()).succeedWithArtifacts(any(), anyString(), anyList(), any());
        verify(fixture.downloads).close();
    }

    private static ProviderResultManifest.Result result(int ordinal, OutputKind kind, boolean primary) {
        return new ProviderResultManifest.Result(ordinal, "node-" + ordinal, kind, primary,
                "https://provider.invalid/output-" + ordinal);
    }

    private static UUID outputIdentity(Task task, int ordinal) {
        return UUID.nameUUIDFromBytes(("agenvas:provider-output:v1:" + task.id() + ":" + ordinal)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static final class Fixture {
        final ObjectMapper mapper = new ObjectMapper();
        final TaskService tasks = mock(TaskService.class);
        final MediaCapabilityService catalog = mock(MediaCapabilityService.class);
        final AssetService assets = mock(AssetService.class);
        final CallLogService callLogs = mock(CallLogService.class);
        final LocalVideoProcessor videoProcessor = mock(LocalVideoProcessor.class);
        final MediaAdapter adapter = mock(MediaAdapter.class);
        final MediaAdapter.ResultDownloads downloads = mock(MediaAdapter.ResultDownloads.class);
        final UUID owner = UUID.randomUUID();
        final MediaCapabilityBinding binding;
        final ObjectNode input = mapper.createObjectNode().put("prompt", "provider prompt");
        final Task task;
        final MediaExecutionWorker worker;

        Fixture(boolean polling) {
            this(polling, Task.Kind.IMAGE_GENERATION);
        }

        Fixture(boolean polling, Task.Kind kind) {
            this(polling, kind, kind == Task.Kind.AUDIO_GENERATION ? "MOCK_AUDIO" : "MOCK_IMAGE");
        }

        Fixture(boolean polling, Task.Kind kind, String adapterId) {
            binding = new MediaCapabilityBinding(UUID.randomUUID(), 1, UUID.randomUUID(), 1,
                    adapterId, "synthetic-mapping");
            input.putObject("mediaInput").put("userRenderedPrompt", "user prompt")
                    .putObject("parameters").putObject("composition").put("ratio", "16:9");
            Instant now = Instant.parse("2026-10-10T00:00:00Z");
            task = new Task(UUID.randomUUID(), UUID.randomUUID(), null, "synthetic-media",
                    kind, polling ? Task.Status.WAITING_PROVIDER : Task.Status.RUNNING,
                    false, input, "frozen-input", null, polling ? "original-request" : null,
                    1, now, WORKER, now.plusSeconds(60), 2, 3, null, now, now, null);
            when(adapter.adapterId()).thenReturn(binding.adapterId());
            worker = new MediaExecutionWorker(tasks, catalog, new MediaAdapterRegistry(List.of(adapter)),
                    assets, mapper, callLogs, videoProcessor);
            when(tasks.claimBoundMedia(WORKER, 1)).thenReturn(List.of(task));
            when(tasks.claimBoundMediaPolls(WORKER, 1)).thenReturn(List.of(task));
            when(tasks.mediaBinding(task)).thenReturn(Optional.of(binding));
            when(tasks.ownerForWorker(task)).thenReturn(owner);
            when(tasks.providerResultManifest(task)).thenReturn(Optional.empty());
            when(tasks.beginSubmission(task, WORKER, binding)).thenReturn(UUID.randomUUID());
            when(catalog.isCurrentBinding(binding, task.kind(), 0)).thenReturn(true);
            var connection = new Connection(binding.connectionId(), "Synthetic connection", MediaPlatform.MOCK, true, 0, 1);
            when(catalog.pinnedSnapshot(binding)).thenReturn(new Snapshot(connection, null, null,
                    binding.adapterId(), binding.mappingSha256(), "{\"settings\":{}}"));
            when(callLogs.record(any(), any(), any())).thenAnswer(call -> {
                Supplier<?> invocation = call.getArgument(1);
                return invocation.get();
            });
            when(assets.archiveTaskImage(any(), any(), any(), any())).thenAnswer(call -> archive(call, Asset.MediaKind.IMAGE));
            when(assets.archiveTaskAudio(any(), any(), any(), any())).thenAnswer(call -> archive(call, Asset.MediaKind.AUDIO));
            when(assets.archiveTaskVideo(any(), any(), any(), any())).thenAnswer(call -> archive(call, Asset.MediaKind.VIDEO));
        }

        ProviderResultManifest manifest(List<ProviderResultManifest.Result> results) {
            return new ProviderResultManifest(ProviderResultManifest.SCHEMA_VERSION, results,
                    mapper.createObjectNode().put("credits", 7));
        }

        void downloads(ProviderResultManifest manifest) {
            when(adapter.openResultDownloads(any())).thenReturn(downloads);
            for (var result : manifest.results()) {
                when(downloads.download(result)).thenReturn(new MediaPayload(new TrackingInputStream(false), "application/octet-stream"));
            }
        }

        Asset asset(UUID id, Asset.MediaKind kind) {
            return new Asset(id, task.projectId(), kind, "synthetic-object", "application/octet-stream",
                    3, "synthetic-sha256", null, null, null, null, null, null, task.createdAt());
        }

        private Asset archive(InvocationOnMock call, Asset.MediaKind kind) throws IOException {
            Supplier<InputStream> input = call.getArgument(3);
            try (InputStream stream = input.get()) {
                stream.readAllBytes();
            }
            return asset(call.getArgument(2), kind);
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private final boolean failOnClose;
        boolean closed;

        TrackingInputStream(boolean failOnClose) {
            super(new byte[] {1, 2, 3});
            this.failOnClose = failOnClose;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (failOnClose) throw new IOException("Cannot close response");
            super.close();
        }
    }
}
