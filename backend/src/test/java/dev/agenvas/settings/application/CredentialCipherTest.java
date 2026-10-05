package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.shared.error.ApiProblemException;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deployment-key rotation retains old decryptability without re-encrypting immutable rows. */
class CredentialCipherTest {

    @Test
    void newWritesUseTheCurrentKeyAndPreviousVersionsRemainReadable() {
        String firstKey = key((byte) 1);
        String secondKey = key((byte) 2);
        CredentialCipher original = new CredentialCipher(
                new CredentialProperties(firstKey, 1, ""));
        UUID oldId = UUID.randomUUID();
        CredentialCipher.Encrypted old = original.encrypt(oldId, 4, "provider-secret-old");

        CredentialCipher rotated = new CredentialCipher(
                new CredentialProperties(secondKey, 2, "1=" + firstKey));
        assertThat(rotated.decrypt(oldId, 4, old)).isEqualTo("provider-secret-old");
        UUID newId = UUID.randomUUID();
        CredentialCipher.Encrypted current = rotated.encrypt(newId, 5, "provider-secret-new");
        assertThat(current.keyVersion()).isEqualTo(2);
        assertThat(rotated.decrypt(newId, 5, current)).isEqualTo("provider-secret-new");

        CredentialCipher missingOldKey = new CredentialCipher(
                new CredentialProperties(secondKey, 2, ""));
        assertThatThrownBy(() -> missingOldKey.requireKeyVersion(1))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code())
                        .isEqualTo("CREDENTIAL_KEY_VERSION_MISSING"));
        assertThatThrownBy(() -> missingOldKey.decrypt(oldId, 4, old))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code())
                        .isEqualTo("CREDENTIAL_KEY_VERSION_MISSING"));
        assertThatThrownBy(() -> rotated.decrypt(UUID.randomUUID(), 4, old))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authentication failed");
    }

    @Test
    void malformedOrDuplicateKeyringFailsAtStartup() {
        assertThatThrownBy(() -> new CredentialCipher(
                new CredentialProperties(key((byte) 2), 2, "1=not-base64")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CredentialCipher(
                new CredentialProperties(key((byte) 2), 2,
                        "1=" + key((byte) 1) + ",1=" + key((byte) 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CredentialCipher(
                new CredentialProperties(key((byte) 2), 2, "2=" + key((byte) 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String key(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
