package dev.agenvas.asset.storage;

import dev.agenvas.settings.application.CredentialCipher.Encrypted;
import java.time.Instant;
import java.util.UUID;

/** Internal destination. Never serialize this record: it contains encrypted credentials. */
public record StorageProfile(UUID id, String name, Provider provider, String endpoint,
        String region, String bucket, String keyPrefix, boolean pathStyle,
        int credentialVersion, Encrypted credentials, String accessKeyMask, Instant createdAt) {
    public enum Provider { ALIYUN_OSS, TENCENT_COS, S3 }
}
