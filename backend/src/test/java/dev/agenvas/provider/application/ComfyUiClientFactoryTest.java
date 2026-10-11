package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.ConnectionVersion;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.settings.application.CredentialProperties;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

/** Synthetic endpoints and real AES-GCM prove the immutable connection boundary without HTTP calls. */
class ComfyUiClientFactoryTest {
    private static final String ENDPOINT = "https://comfy.example.com/proxy/synthetic-path";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void historicalPlaintextOriginDoesNotNeedACredentialKey() {
        CredentialCipher cipher = mock(CredentialCipher.class);
        var version = new ConnectionVersion(UUID.randomUUID(), 3, ENDPOINT, fingerprint(ENDPOINT),
                null, null, null, null);

        assertThat(new ComfyUiClientFactory(cipher, mapper).create(version).originSha256())
                .isEqualTo(version.originSha256());
        verifyNoInteractions(cipher);
    }

    @Test
    void encryptedEndpointIsUsedInsteadOfThePublicRedactedOrigin() {
        CredentialCipher cipher = cipher();
        var version = encryptedVersion(cipher);

        assertThat(new ComfyUiClientFactory(cipher, mapper).create(version).originSha256())
                .isEqualTo(fingerprint(ENDPOINT));
        assertThat(version.origin()).isEqualTo("https://comfy.example.com/[configured-path]");
    }

    @ParameterizedTest
    @EnumSource(InvalidOrigin.class)
    void missingOriginOrFingerprintAndMismatchedFingerprintAreRejected(InvalidOrigin invalid) {
        String origin = invalid == InvalidOrigin.MISSING_ORIGIN ? null : ENDPOINT;
        String hash = switch (invalid) {
            case MISSING_ORIGIN -> fingerprint(ENDPOINT);
            case MISSING_FINGERPRINT -> null;
            case MISMATCHED_FINGERPRINT -> "0".repeat(64);
        };
        var version = new ConnectionVersion(UUID.randomUUID(), 3, origin, hash, null, null, null, null);

        assertThatThrownBy(() -> new ComfyUiClientFactory(cipher(), mapper).create(version))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(invalid == InvalidOrigin.MISMATCHED_FINGERPRINT
                        ? "Pinned ComfyUI origin fingerprint differs" : "Pinned ComfyUI origin is missing");
    }

    @ParameterizedTest
    @EnumSource(SubstitutedIdentity.class)
    void encryptedEndpointCannotBeMovedToAnotherConnectionOrVersion(SubstitutedIdentity substitution) {
        CredentialCipher cipher = cipher();
        ConnectionVersion original = encryptedVersion(cipher);
        var moved = new ConnectionVersion(substitution == SubstitutedIdentity.CONNECTION
                        ? UUID.randomUUID() : original.connectionId(),
                original.version() + (substitution == SubstitutedIdentity.VERSION ? 1 : 0),
                original.origin(), original.originSha256(), original.credentialCiphertext(),
                original.credentialNonce(), original.credentialKeyVersion(), null);

        assertThatThrownBy(() -> new ComfyUiClientFactory(cipher, mapper).create(moved))
                .isInstanceOf(IllegalStateException.class).hasMessage("Credential authentication failed");
    }

    @Test
    void unavailableHistoricalKeyIsNotReplacedByThePublicOrigin() {
        ConnectionVersion version = encryptedVersion(cipher());
        var missingKey = new CredentialCipher(new CredentialProperties(null, 1, null));

        assertThatThrownBy(() -> new ComfyUiClientFactory(missingKey, mapper).create(version))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CREDENTIAL_KEY_VERSION_MISSING"));
    }

    private ConnectionVersion encryptedVersion(CredentialCipher cipher) {
        UUID id = UUID.randomUUID();
        var encrypted = cipher.encryptMedia(id, 3, ENDPOINT);
        return new ConnectionVersion(id, 3, "https://comfy.example.com/[configured-path]",
                fingerprint(ENDPOINT), encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(), null);
    }

    private CredentialCipher cipher() {
        return new CredentialCipher(new CredentialProperties(
                Base64.getEncoder().encodeToString(new byte[32]), 1, null));
    }

    private String fingerprint(String origin) {
        return new ComfyUiClient(new ComfyUiProperties(origin), mapper).originSha256();
    }

    enum InvalidOrigin { MISSING_ORIGIN, MISSING_FINGERPRINT, MISMATCHED_FINGERPRINT }
    enum SubstitutedIdentity { CONNECTION, VERSION }
}
