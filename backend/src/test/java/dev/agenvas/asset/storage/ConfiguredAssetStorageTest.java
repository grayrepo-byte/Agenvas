package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ConfiguredAssetStorageTest {
    @TempDir Path root;
    @Test void legacyInstalledTaskResultRemainsLocalAfterSwitchingTheDefault() throws Exception {
        var local = new LocalAssetStorage(new AssetProperties(root), null, new ObjectMapper());
        UUID project = UUID.randomUUID(), asset = UUID.randomUUID();
        var output = new java.io.ByteArrayOutputStream();
        ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", output);
        var bytes = local.storeImage(project, asset, new ByteArrayInputStream(output.toByteArray()));
        var repository = mock(StorageRepository.class);
        when(repository.route(project, asset)).thenReturn(Optional.empty());
        when(repository.pinLocal(project, asset, "IMAGE")).thenReturn(new StorageRepository.Route(null, "IMAGE", null, false));
        var cloud = mock(ObjectStorageClient.class);
        var storage = new ConfiguredAssetStorage(local, repository, mock(StorageSettingsService.class), cloud, new ObjectMapper(), Clock.systemUTC());
        assertThat(storage.recoverImage(project, asset)).contains(bytes);
        verify(repository).pinLocal(project, asset, "IMAGE");
        verify(repository, never()).pin(any(), any(), any());
        verifyNoInteractions(cloud);
        assertThat(Files.exists(local.checkedPath(bytes.objectKey()))).isTrue();
    }
    @Test void failedArchiveCheckpointLeavesVerifiedStagingBytesForRecovery() throws Exception {
        var local = new LocalAssetStorage(new AssetProperties(root), null, new ObjectMapper());
        UUID project = UUID.randomUUID(), asset = UUID.randomUUID();
        var repository = mock(StorageRepository.class);
        when(repository.pin(project, asset, "IMAGE")).thenReturn(new StorageRepository.Route(UUID.randomUUID(), "IMAGE", null, false));
        doThrow(new IllegalStateException("Database unavailable")).when(repository).checkpoint(eq(project), eq(asset), anyString());
        var cloud = mock(ObjectStorageClient.class);
        var storage = new ConfiguredAssetStorage(local, repository, mock(StorageSettingsService.class), cloud, new ObjectMapper(), Clock.systemUTC());
        var output = new java.io.ByteArrayOutputStream();
        ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", output);
        assertThatThrownBy(() -> storage.storeImage(project, asset, new ByteArrayInputStream(output.toByteArray())))
                .isInstanceOf(IllegalStateException.class).hasMessage("Database unavailable");
        assertThat(local.recoverImage(project, asset)).isPresent();
        verifyNoInteractions(cloud);
    }
    @Test void remoteFileArchivesUseThePinnedRouteButRequireAnIndependentRelay() {
        var repository = mock(StorageRepository.class); var settings = mock(StorageSettingsService.class); var client = mock(RemoteStorageClient.class);
        var mapper = new ObjectMapper(); UUID project = UUID.randomUUID(), asset = UUID.randomUUID(), profile = UUID.randomUUID();
        String key = project + "/" + asset + ".png";
        var receipt = new ConfiguredAssetStorage.Archive(1,"IMAGE",key,"image/png",4,"hash",4,4,null,null,null,null);
        when(repository.route(project,asset)).thenReturn(Optional.of(new StorageRepository.Route(profile,"IMAGE",mapper.writeValueAsString(receipt),true)));
        var storage = new ConfiguredAssetStorage(mock(LocalAssetStorage.class),repository,settings,client,mapper,Clock.systemUTC());
        for (var protocol : java.util.List.of(StorageProfile.Provider.SMB,StorageProfile.Provider.WEBDAV)) {
            when(settings.requireProfile(profile)).thenReturn(new StorageProfile(profile,"NAS",protocol,"synthetic-endpoint","","","",false,1,null,"masked",Instant.EPOCH));
            assertThat(storage.cloudObject("objects/"+profile+"/"+key)).isEmpty();
            storage.verify("objects/"+profile+"/"+key,4,"hash");
        }
        verify(client,times(2)).verify(any(),eq(profile+"/"+key),eq(4L),eq("hash"));
    }
    @Test void cacheCleanupDeletesOnlyIdleProcessingCopiesAndNeverLocalArchivesOrLinks() throws Exception {
        Path cache = root.resolve(".cloud-cache"); Files.createDirectories(cache);
        Path expired = Files.writeString(cache.resolve("expired"), "old");
        Path fresh = Files.writeString(cache.resolve("fresh"), "new");
        Path original = Files.writeString(root.resolve("original.png"), "local-original");
        Path link = Files.createSymbolicLink(cache.resolve("symbolic-link"), original);
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        Files.setLastModifiedTime(expired, java.nio.file.attribute.FileTime.from(now.minus(Duration.ofHours(25))));
        Files.setLastModifiedTime(fresh, java.nio.file.attribute.FileTime.from(now));
        var sweeper = new ObjectStorageCache(new AssetProperties(root), Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(24));
        sweeper.cleanup();
        assertThat(Files.exists(expired)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
        assertThat(Files.readString(original)).isEqualTo("local-original");
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }
}
