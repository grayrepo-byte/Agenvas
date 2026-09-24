package dev.agenvas.identity.application;

import java.time.Instant;
import java.util.UUID;

/** Immutable application view of the single administrator account. */
public record AdminAccount(
        UUID id,
        String loginName,
        String passwordHash,
        Status status,
        Instant createdAt,
        Instant passwordChangedAt,
        long version) {

    /** Persisted administrator lifecycle states. */
    public enum Status {
        ACTIVE,
        DISABLED
    }
}
