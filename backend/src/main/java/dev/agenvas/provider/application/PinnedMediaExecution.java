package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.settings.application.CredentialCipher;
import org.springframework.stereotype.Service;

/** Opens historical configuration and its credential only for the executing adapter. */
@Service
final class PinnedMediaExecution {
    private final PinnedMediaPublications publications;
    private final CredentialCipher cipher;

    PinnedMediaExecution(PinnedMediaPublications publications, CredentialCipher cipher) {
        this.publications = publications;
        this.cipher = cipher;
    }

    Configuration open(MediaCapabilityBinding binding, String adapterId, String missingCredential) {
        if (!adapterId.equals(binding.adapterId()))
            throw new IllegalStateException("Pinned binding belongs to another adapter");
        var snapshot = publications.snapshot(binding);
        var version = snapshot.connectionVersion();
        if (version.credentialCiphertext() == null || version.credentialNonce() == null
                || version.credentialKeyVersion() == null)
            throw new IllegalStateException(missingCredential);
        var key = cipher.decryptMedia(version.connectionId(), version.version(),
                new CredentialCipher.Encrypted(version.credentialCiphertext(), version.credentialNonce(),
                        version.credentialKeyVersion()));
        return new Configuration(snapshot.specJson(), version.origin(), key);
    }

    /** Invocation-local only. Even synthetic diagnostic text must omit the entire private configuration. */
    record Configuration(String specJson, String origin, String credential) {
        @Override
        public String toString() {
            return "PinnedMediaExecution.Configuration[redacted]";
        }
    }
}
