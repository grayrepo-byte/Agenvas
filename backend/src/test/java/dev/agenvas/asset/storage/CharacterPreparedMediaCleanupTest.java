package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Synthetic cloud receipts verify cleanup uses frozen keys without uploading unfinished media. */
class CharacterPreparedMediaCleanupTest {
    @TempDir Path root;
    @Test void audioCleanupDeletesOnlyFrozenReceiptAndLocalPartialBytesWithoutResumingUploads() throws Exception {
        UUID project=UUID.randomUUID(),asset=UUID.randomUUID(),profileId=UUID.randomUUID();
        String key=project+"/"+asset+".wav";
        var local=new LocalAssetStorage(new AssetProperties(root),null,new ObjectMapper());
        Files.createDirectories(root.resolve(project.toString())); Files.writeString(root.resolve(key),"partial");
        var mapper=new ObjectMapper(); var repository=mock(StorageRepository.class); var settings=mock(StorageSettingsService.class); var cloud=mock(RemoteStorageClient.class);
        var profile=new StorageProfile(profileId,"Synthetic",StorageProfile.Provider.WEBDAV,"synthetic-endpoint","","","",false,1,null,"masked",Instant.EPOCH);
        when(settings.requireProfile(profileId)).thenReturn(profile);
        var receipt=new ConfiguredAssetStorage.Archive(1,"AUDIO",key,"audio/wav",7,"synthetic-hash",null,null,1000,null,null,null);
        when(repository.route(project,asset)).thenReturn(Optional.of(new StorageRepository.Route(profileId,"AUDIO",mapper.writeValueAsString(receipt),false)));
        var storage=new ConfiguredAssetStorage(local,repository,settings,cloud,mapper,Clock.systemUTC());
        storage.discardPreparedMedia(project,asset,Asset.MediaKind.AUDIO);
        assertThat(Files.exists(root.resolve(key))).isFalse();
        verify(cloud).delete(profile,profileId+"/"+key); verifyNoMoreInteractions(cloud);
        verify(repository,never()).ready(any(),any());
    }
    @Test void mismatchedReceiptCannotDeleteAnotherCommandsObject() {
        UUID project=UUID.randomUUID(),asset=UUID.randomUUID(),profile=UUID.randomUUID();
        var mapper=new ObjectMapper(); var repository=mock(StorageRepository.class); var cloud=mock(RemoteStorageClient.class);
        var receipt=new ConfiguredAssetStorage.Archive(1,"AUDIO",project+"/"+UUID.randomUUID()+".wav","audio/wav",1,"synthetic",null,null,1,null,null,null);
        when(repository.route(project,asset)).thenReturn(Optional.of(new StorageRepository.Route(profile,"AUDIO",mapper.writeValueAsString(receipt),false)));
        var storage=new ConfiguredAssetStorage(new LocalAssetStorage(new AssetProperties(root),null,mapper),repository,mock(StorageSettingsService.class),cloud,mapper,Clock.systemUTC());
        assertThatThrownBy(() -> storage.discardPreparedMedia(project,asset,Asset.MediaKind.AUDIO)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(cloud);
    }
}
