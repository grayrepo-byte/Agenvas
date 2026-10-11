package dev.agenvas.provider.application;

import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.ConnectionVersion;
import dev.agenvas.settings.application.CredentialCipher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Builds a client only after the immutable connection's private endpoint and fingerprint agree. */
@Component
public class ComfyUiClientFactory {
    private final CredentialCipher cipher;
    private final ObjectMapper mapper;

    public ComfyUiClientFactory(CredentialCipher cipher, ObjectMapper mapper) {
        this.cipher = cipher;
        this.mapper = mapper;
    }

    /** Historical local versions contain plaintext; configured proxy paths are encrypted with versioned AAD. */
    public ComfyUiClient create(ConnectionVersion version) {
        String origin = version.credentialCiphertext() == null ? version.origin()
                : cipher.decryptMedia(version.connectionId(), version.version(),
                        new CredentialCipher.Encrypted(version.credentialCiphertext(),
                                version.credentialNonce(), version.credentialKeyVersion()));
        if (origin == null || version.originSha256() == null) {
            throw new IllegalStateException("Pinned ComfyUI origin is missing");
        }
        ComfyUiClient client = new ComfyUiClient(new ComfyUiProperties(origin), mapper);
        if (!version.originSha256().equals(client.originSha256())) {
            throw new IllegalStateException("Pinned ComfyUI origin fingerprint differs");
        }
        return client;
    }
}
