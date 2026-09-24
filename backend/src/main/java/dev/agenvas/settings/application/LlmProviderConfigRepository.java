package dev.agenvas.settings.application;

import java.util.Optional;

/** Global administrator configuration is versioned under one PostgreSQL counter row. */
public interface LlmProviderConfigRepository {

    /** Locks the counter for an atomic expected-version write. */
    int lockVersion();

    /** Reads the current public or runtime configuration. */
    Optional<LlmProviderConfig> active();

    /** Retains previous encrypted versions for already-running work. */
    Optional<LlmProviderConfig> findVersion(int version);

    /** Publishes exactly one new version under the caller's counter lock. */
    void publish(int expectedVersion, LlmProviderConfig config);

    /** Marks only the still-active tested version as supporting full tool round-trips. */
    boolean markToolCallingVerified(int version);
}
