package dev.agenvas.mcp.application;

import dev.agenvas.mcp.domain.McpScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence boundary for user-owned credentials, including short authorization row locks. */
public interface McpTokenRepository {
    List<StoredToken> list(UUID userId);

    Optional<StoredToken> find(UUID tokenId);

    /** Caller must already hold its project lock, if the command targets a project. */
    Optional<StoredToken> lock(UUID tokenId);

    void insert(StoredToken token);

    /** A compare-and-set update; caller holds the credential row lock. */
    boolean revoke(UUID tokenId, long expectedVersion, Instant revokedAt);

    void recordUse(UUID tokenId, Instant usedAt);

    /** Internal credential data, never serialized as an HTTP or tool result. */
    record StoredToken(UUID id, UUID userId, String name, String secretHash, String tokenHint,
            Set<McpScope> scopes, long version, Instant createdAt, Instant expiresAt,
            Instant revokedAt, Instant lastUsedAt) {
        public StoredToken {
            scopes = Set.copyOf(scopes);
        }

        @Override
        public String toString() {
            return "StoredToken[id=" + id + ", userId=" + userId + ", secretHash=[REDACTED]]";
        }
    }
}
