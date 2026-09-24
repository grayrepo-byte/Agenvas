package dev.agenvas.settings.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Encrypts Provider credentials with a deployment-only AES-256-GCM key and bound config ID. */
@Component
public class CredentialCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final int keyVersion;
    private final Map<Integer, SecretKeySpec> previousKeys;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(CredentialProperties properties) {
        if (properties.keyVersion() < 1) {
            throw new IllegalArgumentException("Credential key version must be positive");
        }
        this.keyVersion = properties.keyVersion();
        String encoded = properties.masterKeyBase64();
        if (encoded == null || encoded.isBlank()) {
            this.key = null;
        } else {
            this.key = decodeKey(encoded);
        }
        Map<Integer, SecretKeySpec> old = new HashMap<>();
        String previous = properties.previousKeys();
        if (previous != null && !previous.isBlank()) {
            for (String entry : previous.split(",", -1)) {
                String[] parts = entry.trim().split("=", 2);
                int version;
                try {
                    version = Integer.parseInt(parts[0].trim());
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("Previous credential key version is invalid");
                }
                if (parts.length != 2 || parts[1].isBlank() || version < 1
                        || version >= keyVersion || old.containsKey(version)) {
                    throw new IllegalArgumentException("Previous credential keyring is invalid");
                }
                old.put(version, decodeKey(parts[1].trim()));
            }
        }
        this.previousKeys = Map.copyOf(old);
    }

    private SecretKeySpec decodeKey(String encoded) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Credential master key must be Base64", invalid);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("Credential master key must contain 32 bytes");
        }
        SecretKeySpec parsed = new SecretKeySpec(decoded, "AES");
        java.util.Arrays.fill(decoded, (byte) 0);
        return parsed;
    }

    /** Stores a random nonce for every immutable version; ciphertext includes the GCM tag. */
    public Encrypted encrypt(UUID configId, int version, String secret) {
        requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(configId, version));
            return new Encrypted(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)),
                    nonce, keyVersion);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Credential encryption failed", failure);
        }
    }

    /** Refuses a swapped, corrupted, or retired-key record instead of returning a bad secret. */
    public String decrypt(UUID configId, int version, Encrypted encrypted) {
        requireKeyVersion(encrypted.keyVersion());
        SecretKeySpec selected = encrypted.keyVersion() == keyVersion
                ? key : previousKeys.get(encrypted.keyVersion());
        if (encrypted.nonce().length != NONCE_BYTES) {
            throw new IllegalStateException("Credential nonce is invalid");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, selected,
                    new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(aad(configId, version));
            return new String(cipher.doFinal(encrypted.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Credential authentication failed", failure);
        }
    }

    /** Checks historical availability before reserving a billable model turn. */
    public void requireKeyVersion(int requestedVersion) {
        if ((requestedVersion != keyVersion && !previousKeys.containsKey(requestedVersion))
                || (requestedVersion == keyVersion && key == null)) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                    "CREDENTIAL_KEY_VERSION_MISSING", "历史凭证密钥不可用",
                    "部署者尚未提供该配置版本对应的解密主密钥。", false);
        }
    }

    private byte[] aad(UUID configId, int version) {
        return ("agenvas:llm-provider-config:" + configId + ":" + version)
                .getBytes(StandardCharsets.UTF_8);
    }

    private void requireKey() {
        if (key == null) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                    "CREDENTIAL_MASTER_KEY_MISSING", "密钥存储未就绪",
                    "部署者尚未配置凭证加密主密钥。", false);
        }
    }

    /** Database fields, never serialized to a public response. */
    public record Encrypted(byte[] ciphertext, byte[] nonce, int keyVersion) {}
}
