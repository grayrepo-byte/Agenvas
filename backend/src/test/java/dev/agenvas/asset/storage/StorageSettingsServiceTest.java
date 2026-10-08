package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.settings.application.CredentialProperties;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskStorageReferences;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StorageSettingsServiceTest {
    @Test void endpointsRequireHttpsAndNeverCarryCredentialsPathsOrQueryStrings() {
        assertThat(StorageSettingsService.normalizeEndpoint(" https://s3.example.com/ ")).isEqualTo("https://s3.example.com");
        for (String endpoint : new String[] { "http://s3.example.com", "https://key@s3.example.com", "https://s3.example.com/bucket",
                "https://s3.example.com?token=secret", "https://s3.example.com#fragment", "file:///tmp", "broken" })
            assertThatThrownBy(() -> StorageSettingsService.normalizeEndpoint(endpoint)).isInstanceOf(ApiProblemException.class);
    }
    @Test void concurrentUpdateFailsBeforeEncryptionOrPersistence() {
        var repository = mock(StorageRepository.class); when(repository.lockVersion()).thenReturn(2);
        var service = new StorageSettingsService(repository, mock(CredentialCipher.class), Clock.systemUTC(), mock(TaskStorageReferences.class));
        assertThatThrownBy(() -> service.activate(1, null)).isInstanceOf(ApiProblemException.class)
                .satisfies(f -> assertThat(((ApiProblemException) f).code()).isEqualTo("STORAGE_VERSION_CONFLICT"));
        verify(repository, never()).advance(anyInt(), any());
    }
    @Test void storageSecretsUseSeparateAuthenticatedNamespace() {
        var cipher = new CredentialCipher(new CredentialProperties(Base64.getEncoder().encodeToString(new byte[32]), 1, ""));
        UUID id = UUID.randomUUID();
        var secret = cipher.encryptStorage(id, 1, "access-id\nsecret-value");
        assertThat(cipher.decryptStorage(id, 1, secret)).isEqualTo("access-id\nsecret-value");
        assertThatThrownBy(() -> cipher.decrypt(id, 1, secret)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decryptStorage(id, 2, secret)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decryptStorage(UUID.randomUUID(), 1, secret)).isInstanceOf(IllegalStateException.class);
    }
    @Test void relaySelectionHasIndependentCasAndLeavesTheArchiveSelectorUntouched() {
        var repository = mock(StorageRepository.class);
        UUID archive = UUID.randomUUID(), relay = UUID.randomUUID();
        when(repository.lockVersion()).thenReturn(4);
        when(repository.profile(relay)).thenReturn(java.util.Optional.of(mock(StorageProfile.class)));
        when(repository.state()).thenReturn(new StorageRepository.State(5, archive, relay));
        when(repository.profiles()).thenReturn(java.util.List.of());
        var service = new StorageSettingsService(repository, mock(CredentialCipher.class), Clock.systemUTC(), mock(TaskStorageReferences.class));
        var result = service.activateRelay(4, relay);
        assertThat(result.activeProfileId()).isEqualTo(archive);
        assertThat(result.relayProfileId()).isEqualTo(relay);
        verify(repository).advanceRelay(4, relay);
        verify(repository, never()).advance(anyInt(), any());
        assertThatThrownBy(() -> service.activateRelay(3, null)).isInstanceOf(ApiProblemException.class)
                .satisfies(f -> assertThat(((ApiProblemException) f).code()).isEqualTo("STORAGE_VERSION_CONFLICT"));
        verify(repository, never()).advanceRelay(3, null);
    }

    @Test void editsKeepCredentialsWhenOmittedAndRetainTheConnectionIdentity() {
        var repository = mock(StorageRepository.class);
        var cipher = spy(new CredentialCipher(new CredentialProperties(Base64.getEncoder().encodeToString(new byte[32]), 1, "")));
        var old = profile(cipher, "oss-cn-chengdu"); var usage = mock(TaskStorageReferences.class);
        when(repository.lockVersion()).thenReturn(3);
        when(repository.lockProfile(old.id())).thenReturn(java.util.Optional.of(old));
        when(repository.state()).thenReturn(new StorageRepository.State(4, old.id(), old.id()));
        when(repository.profiles()).thenReturn(java.util.List.of());
        clearInvocations(cipher);
        var service = new StorageSettingsService(repository,cipher,Clock.systemUTC(),usage);
        service.update(3,old.id(),"修正连接",old.provider(),old.endpoint(),"cn-chengdu",old.bucket(),old.keyPrefix(),false,null,null);
        var updated = org.mockito.ArgumentCaptor.forClass(StorageProfile.class);
        verify(repository).updateProfile(updated.capture());
        assertThat(updated.getValue().region()).isEqualTo("cn-chengdu");
        assertThat(updated.getValue().credentials()).isSameAs(old.credentials());
        assertThat(updated.getValue().credentialVersion()).isEqualTo(old.credentialVersion());
        assertThat(updated.getValue().id()).isEqualTo(old.id());
        verify(cipher,never()).encryptStorage(any(),anyInt(),any());
    }
    @Test void taskReferencesBlockRelocationAndDeletionButAllowRenamingAndCredentialRotation() {
        var repository = mock(StorageRepository.class);
        var cipher = new CredentialCipher(new CredentialProperties(Base64.getEncoder().encodeToString(new byte[32]),1,""));
        var old = profile(cipher); var usage = mock(TaskStorageReferences.class);
        when(repository.lockVersion()).thenReturn(3);
        when(repository.lockProfile(old.id())).thenReturn(java.util.Optional.of(old));
        when(repository.state()).thenReturn(new StorageRepository.State(4,null,null));
        when(repository.profiles()).thenReturn(java.util.List.of(old));
        when(usage.referencesStorageProfile(old.id())).thenReturn(true);
        var service = new StorageSettingsService(repository,cipher,Clock.systemUTC(),usage);
        assertThatThrownBy(() -> service.delete(3,old.id())).isInstanceOf(ApiProblemException.class)
                .satisfies(e -> assertThat(((ApiProblemException)e).code()).isEqualTo("STORAGE_PROFILE_IN_USE"));
        assertThatThrownBy(() -> service.update(3,old.id(),"renamed",old.provider(),old.endpoint(),"cn-beijing",old.bucket(),
                old.keyPrefix(),false,null,null)).isInstanceOf(ApiProblemException.class);
        verify(repository,never()).deleteProfile(any()); verify(repository,never()).detachProfile(anyInt(),any());
        service.update(3,old.id(),"renamed",old.provider(),old.endpoint(),old.region(),old.bucket(),old.keyPrefix(),false,"new-access-id","new-secret-value");
        var updated = org.mockito.ArgumentCaptor.forClass(StorageProfile.class);
        verify(repository).updateProfile(updated.capture());
        assertThat(updated.getValue().name()).isEqualTo("renamed");
        assertThat(updated.getValue().region()).isEqualTo(old.region());
        assertThat(cipher.decryptStorage(old.id(),2,updated.getValue().credentials())).isEqualTo("new-access-id\nnew-secret-value");
        assertThat(service.status().profiles().getFirst().inUse()).isTrue();
    }
    @Test void staleDeletesAndIncompleteKeysCannotWriteAndOssRegionRejectsEndpointPrefix() {
        var repository = mock(StorageRepository.class); when(repository.lockVersion()).thenReturn(3);
        var cipher = new CredentialCipher(new CredentialProperties(Base64.getEncoder().encodeToString(new byte[32]),1,""));
        var old = profile(cipher);
        when(repository.lockProfile(old.id())).thenReturn(java.util.Optional.of(old));
        var service = new StorageSettingsService(repository,cipher,Clock.systemUTC(),mock(TaskStorageReferences.class));
        assertThatThrownBy(() -> service.delete(2,old.id())).isInstanceOf(ApiProblemException.class);
        verify(repository,never()).lockProfile(any());
        assertThatThrownBy(() -> service.update(3,old.id(),old.name(),old.provider(),old.endpoint(),old.region(),old.bucket(),
                old.keyPrefix(),false,"new-id",null)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.create(3,"new",old.provider(),old.endpoint(),"oss-cn-chengdu",old.bucket(),"",false,
                "new-id","new-secret")).isInstanceOf(ApiProblemException.class);
        verify(repository,never()).updateProfile(any()); verify(repository,never()).insertProfile(any());
    }
    private static StorageProfile profile(CredentialCipher cipher) {
        return profile(cipher,"cn-chengdu");
    }
    private static StorageProfile profile(CredentialCipher cipher, String region) {
        UUID id=UUID.randomUUID();
        return new StorageProfile(id,"cloud",StorageProfile.Provider.ALIYUN_OSS,"https://oss-cn-chengdu.aliyuncs.com",region,
                "test-bucket","agenvas",false,1,cipher.encryptStorage(id,1,"test-id\ntest-secret"),"••••t-id",Instant.EPOCH);
    }
}
