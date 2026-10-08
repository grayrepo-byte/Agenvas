package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MediaRelayServiceTest {
    private final AssetService assets = mock(AssetService.class);
    private final ConfiguredAssetStorage storage = mock(ConfiguredAssetStorage.class);
    private final StorageSettingsService settings = mock(StorageSettingsService.class);
    private final ObjectStorageClient cloud = mock(ObjectStorageClient.class);
    private final MediaRelayRepository copies = mock(MediaRelayRepository.class);
    private final MediaRelayReservationService reservations = mock(MediaRelayReservationService.class);
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
    private final MediaRelayService relay = new MediaRelayService(assets, storage, settings, cloud, copies, Clock.fixed(NOW, ZoneOffset.UTC), reservations);
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), assetId = UUID.randomUUID();
    private final StorageProfile profile = new StorageProfile(UUID.randomUUID(), "test relay", StorageProfile.Provider.S3,
            "https://s3.example.com", "us-east-1", "test-bucket", "test", true, 1, null, "masked", NOW);
    private Asset asset(String key) {
        return new Asset(assetId, project, Asset.MediaKind.VIDEO, key, "video/mp4", 100, "hash", 1280, 720, 4000,
                null, null, null, NOW);
    }
    @Test void localReferenceRequiresAnIndependentRelayAndPinsItWithoutNetwork() {
        Asset asset = asset("local.mp4");
        when(storage.cloudObject(asset.objectKey())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> relay.pinProfile(asset)).isInstanceOf(ApiProblemException.class)
                .satisfies(f -> assertThat(((ApiProblemException) f).code()).isEqualTo("MEDIA_RELAY_REQUIRED"));
        when(settings.relayProfileId()).thenReturn(profile.id());
        when(settings.requireProfile(profile.id())).thenReturn(profile);
        assertThat(relay.pinProfile(asset)).isEqualTo(profile.id());
        verifyNoInteractions(cloud, copies, assets);
    }
    @Test void cloudOriginalIsSignedAtItsRecordedLocationWithoutUploadingOrMaterializing() {
        Asset asset = asset("objects/original.mp4");
        when(assets.requireReadyMedia(owner, project, assetId, Asset.MediaKind.VIDEO)).thenReturn(asset);
        when(storage.cloudObject(asset.objectKey())).thenReturn(Optional.of(new ConfiguredAssetStorage.CloudObject(profile, "original-key.mp4")));
        when(cloud.signedGet(profile, "original-key.mp4", MediaRelayService.URL_LIFETIME)).thenReturn("signed-url");
        assertThat(relay.pinProfile(asset)).isNull();
        assertThat(relay.signedVideo(owner, project, assetId, null)).isEqualTo("signed-url");
        verify(assets, never()).get(any(), any(), any());
        verify(cloud, never()).put(any(), any(), any(), any(), anyLong(), any());
        verifyNoInteractions(settings, copies);
    }
    @Test void localCopyIsRegisteredBeforePutAndUsesTheFrozenConnection() {
        Asset asset = asset("local.mp4");
        when(assets.requireReadyMedia(owner, project, assetId, Asset.MediaKind.VIDEO)).thenReturn(asset);
        when(storage.cloudObject(asset.objectKey())).thenReturn(Optional.empty());
        when(settings.requireProfile(profile.id())).thenReturn(profile);
        when(assets.get(owner, project, assetId)).thenReturn(new AssetService.AssetFile(asset, Path.of("synthetic.mp4")));
        when(cloud.signedGet(eq(profile), anyString(), eq(MediaRelayService.URL_LIFETIME))).thenReturn("signed-url");
        assertThat(relay.signedVideo(owner, project, assetId, profile.id())).isEqualTo("signed-url");
        var capture = ArgumentCaptor.forClass(MediaRelayRepository.Copy.class);
        var order = inOrder(copies, cloud);
        order.verify(cloud).requirePublic(profile);
        order.verify(copies).register(capture.capture(), eq(NOW));
        var copy = capture.getValue();
        assertThat(copy.profileId()).isEqualTo(profile.id());
        assertThat(copy.expiresAt()).isEqualTo(NOW.plus(MediaRelayService.COPY_RETENTION));
        assertThat(copy.key()).startsWith("test/media-relay/").endsWith(".mp4");
        order.verify(cloud).put(profile, copy.key(), Path.of("synthetic.mp4"), "video/mp4", 100, "hash");
        order.verify(cloud).signedGet(profile, copy.key(), MediaRelayService.URL_LIFETIME);
        verify(settings, never()).relayProfileId();
    }
    @Test void imageUploadRegistersBeforeNetworkAndDeletesPreparedLocalFile() throws Exception {
        byte[] image = new byte[] { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4 };
        var copy = new MediaRelayRepository.Copy(UUID.randomUUID(), profile.id(), "test/media-relay/reference.png", NOW.plus(MediaRelayService.COPY_RETENTION));
        when(reservations.reserve(profile.id(), ".png")).thenReturn(new MediaRelayReservationService.Reservation(profile, copy));
        when(cloud.signedGet(profile, copy.key(), MediaRelayService.URL_LIFETIME)).thenReturn("https://example.com/image?X-Amz-Signature=synthetic");
        doAnswer(call -> {
            Path file = call.getArgument(2);
            assertThat(java.nio.file.Files.readAllBytes(file)).isEqualTo(image);
            return null;
        }).when(cloud).put(eq(profile), eq(copy.key()), any(Path.class), eq("image/png"), eq(8L), anyString());
        assertThat(relay.signedImage(profile.id(), image, "image/png")).startsWith("https://example.com/image");
        var file = ArgumentCaptor.forClass(Path.class);
        var order = inOrder(reservations, cloud);
        order.verify(reservations).reserve(profile.id(), ".png");
        order.verify(cloud).requirePublic(profile);
        order.verify(cloud).put(eq(profile), eq(copy.key()), file.capture(), eq("image/png"), eq(8L), anyString());
        order.verify(cloud).signedGet(profile, copy.key(), MediaRelayService.URL_LIFETIME);
        assertThat(java.nio.file.Files.exists(file.getValue())).isFalse();
    }

    @Test void failedImageUploadLeavesCleanupIdentityAndDoesNotSign() {
        var copy = new MediaRelayRepository.Copy(UUID.randomUUID(), profile.id(), "test/media-relay/failure.png", NOW.plus(MediaRelayService.COPY_RETENTION));
        when(reservations.reserve(profile.id(), ".png")).thenReturn(new MediaRelayReservationService.Reservation(profile, copy));
        doThrow(new IllegalStateException("synthetic upload failure")).when(cloud).put(any(), any(), any(), any(), anyLong(), any());
        assertThatThrownBy(() -> relay.signedImage(profile.id(), new byte[] { 1 }, "image/png")).isInstanceOf(IllegalStateException.class);
        verify(cloud, never()).signedGet(any(), any(), any());
        verify(copies, never()).removed(any());
    }

    @Test void failedDeleteRetainsItsDurableCleanupRowAndNeverTouchesArchiveObjects() {
        var copy = new MediaRelayRepository.Copy(UUID.randomUUID(), profile.id(), "test/media-relay/copy.mp4", NOW.minusSeconds(1));
        when(copies.expired(NOW)).thenReturn(List.of(copy));
        when(settings.requireProfile(profile.id())).thenReturn(profile);
        doThrow(new IllegalStateException("synthetic failure")).when(cloud).delete(profile, copy.key());
        relay.cleanup();
        verify(copies, never()).removed(any());
        doNothing().when(cloud).delete(profile, copy.key());
        relay.cleanup();
        verify(copies).removed(copy);
        verifyNoInteractions(storage, assets);
    }
}
