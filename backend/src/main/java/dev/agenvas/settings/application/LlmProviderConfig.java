package dev.agenvas.settings.application;

import java.time.Instant;
import java.util.UUID;

/** One immutable LLM configuration version; only the active pointer may change. */
public record LlmProviderConfig(UUID id, int version, String endpoint, String modelId,
        byte[] credentialCiphertext, byte[] credentialNonce, int keyVersion,
        String keyMask, boolean toolCallingVerified, boolean active, Instant createdAt) {}
