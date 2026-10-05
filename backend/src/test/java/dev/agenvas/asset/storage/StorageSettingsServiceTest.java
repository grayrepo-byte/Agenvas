package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.settings.application.CredentialProperties;
import dev.agenvas.shared.error.ApiProblemException;
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
        var service = new StorageSettingsService(repository, mock(CredentialCipher.class), Clock.systemUTC());
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
        var service = new StorageSettingsService(repository, mock(CredentialCipher.class), Clock.systemUTC());
        var result = service.activateRelay(4, relay);
        assertThat(result.activeProfileId()).isEqualTo(archive);
        assertThat(result.relayProfileId()).isEqualTo(relay);
        verify(repository).advanceRelay(4, relay);
        verify(repository, never()).advance(anyInt(), any());
        assertThatThrownBy(() -> service.activateRelay(3, null)).isInstanceOf(ApiProblemException.class)
                .satisfies(f -> assertThat(((ApiProblemException) f).code()).isEqualTo("STORAGE_VERSION_CONFLICT"));
        verify(repository, never()).advanceRelay(3, null);
    }

}
