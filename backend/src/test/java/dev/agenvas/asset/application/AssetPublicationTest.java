package dev.agenvas.asset.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.asset.storage.AssetStorage;
import dev.agenvas.event.application.ProjectEventRepository;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.event.domain.ProjectEvent;
import dev.agenvas.project.application.ProjectService;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

/** Storage is mocked; these cases exercise authorization, publication and failure cleanup. */
class AssetPublicationTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final String ORIGINAL = "synthetic/original";
    private static final String THUMBNAIL = "synthetic/thumbnail";
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00.123456789Z");

    private final ProjectService projects = mock(ProjectService.class);
    private final AssetRepository repository = mock(AssetRepository.class);
    private final AssetStorage storage = mock(AssetStorage.class);
    private final ProjectEventRepository events = mock(ProjectEventRepository.class);
    private AssetService service;

    @BeforeEach
    void setup() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AssetService(projects, repository, storage,
                new ProjectEventService(events, clock, mock(ApplicationEventPublisher.class)),
                new ObjectMapper(), clock);
        when(events.lockCurrentSequence(OWNER, PROJECT)).thenReturn(OptionalLong.of(0));
        when(events.advanceSequence(OWNER, PROJECT, 0, 1)).thenReturn(true);
        when(repository.find(eq(PROJECT), any())).thenReturn(Optional.empty());
        when(storage.storeImage(eq(PROJECT), any(), any())).thenReturn(new LocalAssetStorage.StoredImage(
                ORIGINAL, "image/png", 3, "synthetic-hash", 4, 3, THUMBNAIL, 2, "synthetic-preview-hash"));
        when(storage.storeVideo(eq(PROJECT), any(), any())).thenReturn(new LocalAssetStorage.StoredVideo(
                ORIGINAL, 3, "synthetic-hash", 4, 3, 1000, THUMBNAIL, 2, "synthetic-preview-hash"));
        when(storage.storeAudio(eq(PROJECT), any(), any())).thenReturn(new LocalAssetStorage.StoredAudio(
                ORIGINAL, "audio/mpeg", 3, "synthetic-hash", 1000));
        when(storage.recoverImage(eq(PROJECT), any())).thenReturn(Optional.empty());
        when(storage.recoverVideo(eq(PROJECT), any())).thenReturn(Optional.empty());
        when(storage.recoverAudio(eq(PROJECT), any())).thenReturn(Optional.empty());
        when(storage.withTaskImageLock(eq(PROJECT), any(), any()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
        when(storage.withTaskVideoLock(eq(PROJECT), any(), any()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
        when(storage.withTaskAudioLock(eq(PROJECT), any(), any()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
    }

    @ParameterizedTest
    @EnumSource(Asset.MediaKind.class)
    void uploadsPublishTheSameMinimalReadyEvent(Asset.MediaKind kind) {
        Asset asset = archive(kind, false);
        assertThat(asset.mediaKind()).isEqualTo(kind);
        assertThat(asset.projectId()).isEqualTo(PROJECT);
        assertThat(asset.createdAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
        verify(repository).insert(asset);
        var published = ArgumentCaptor.forClass(ProjectEvent.class);
        verify(events).insert(published.capture());
        ProjectEvent event = published.getValue();
        assertThat(event.type()).isEqualTo("asset.ready");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.aggregateId()).isEqualTo(asset.id());
        assertThat(event.aggregateVersion()).isZero();
        assertThat(event.payload().propertyNames()).containsExactly("assetId", "contentType", "byteSize");
        assertThat(event.payload().path("assetId").asText()).isEqualTo(asset.id().toString());
        assertThat(event.payload().path("contentType").asText()).isEqualTo(asset.contentType());
        assertThat(event.payload().path("byteSize").asLong()).isEqualTo(asset.byteSize());
        verify(storage, never()).discard(any());
    }

    @ParameterizedTest
    @EnumSource(Asset.MediaKind.class)
    void failedUserRegistrationCleansInstalledBytesAndPublishesNoEvent(Asset.MediaKind kind) {
        var failure = new IllegalStateException("synthetic READY insert failure");
        doThrow(failure).when(repository).insert(any());
        assertThatThrownBy(() -> archive(kind, false)).isSameAs(failure);
        verify(storage).discard(ORIGINAL);
        if (kind != Asset.MediaKind.AUDIO) verify(storage).discard(THUMBNAIL);
        else verify(storage, never()).discard(THUMBNAIL);
        verify(events, never()).insert(any());
        verify(events, never()).advanceSequence(any(), any(), anyLong(), anyLong());
    }

    @ParameterizedTest
    @EnumSource(Asset.MediaKind.class)
    void taskRegistrationCanFinishAfterArchivalAndRetainsFilesOnDatabaseFailure(Asset.MediaKind kind) {
        doThrow(new IllegalStateException("archived project")).when(projects).requireActiveProject(OWNER, PROJECT);
        assertThat(archive(kind, true).mediaKind()).isEqualTo(kind);
        var failure = new IllegalStateException("synthetic READY insert failure");
        doThrow(failure).when(repository).insert(any());
        assertThatThrownBy(() -> archive(kind, true)).isSameAs(failure);
        verify(projects, never()).requireActiveProject(OWNER, PROJECT);
        verify(storage, never()).discard(any());
        verify(events).insert(any());
    }

    @ParameterizedTest
    @EnumSource(value = Asset.MediaKind.class, names = {"IMAGE", "VIDEO"})
    void thumbnailCleanupStillRunsWhenOriginalCleanupFails(Asset.MediaKind kind) {
        doThrow(new IllegalStateException("synthetic READY insert failure")).when(repository).insert(any());
        var cleanupFailure = new IllegalStateException("synthetic original cleanup failure");
        doThrow(cleanupFailure).when(storage).discard(ORIGINAL);
        assertThatThrownBy(() -> archive(kind, false)).isSameAs(cleanupFailure);
        verify(storage).discard(THUMBNAIL);
    }

    private Asset archive(Asset.MediaKind kind, boolean task) {
        Supplier<java.io.InputStream> input = () -> new ByteArrayInputStream(new byte[] {1, 2, 3});
        UUID taskId = UUID.randomUUID();
        return switch (kind) {
            case IMAGE -> task ? service.archiveTaskImage(OWNER, PROJECT, taskId, input)
                    : service.archiveImage(OWNER, PROJECT, input.get());
            case VIDEO -> task ? service.archiveTaskVideo(OWNER, PROJECT, taskId, input)
                    : service.archiveVideo(OWNER, PROJECT, input.get());
            case AUDIO -> task ? service.archiveTaskAudio(OWNER, PROJECT, taskId, input)
                    : service.archiveAudio(OWNER, PROJECT, input.get());
        };
    }
}
