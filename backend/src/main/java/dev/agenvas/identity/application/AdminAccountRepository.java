package dev.agenvas.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for the single administrator account. */
public interface AdminAccountRepository {

    /** Locks the installation bootstrap row until the surrounding transaction completes. */
    void lockSetup();

    /** Returns whether an active administrator already owns this installation. */
    boolean hasAdminAccount();

    /** Finds an active administrator by the normalized login name. */
    Optional<AdminAccount> findActiveByLoginName(String loginName);

    /** Inserts the first active administrator. Database constraints arbitrate setup races. */
    void createAdmin(UUID id, String loginName, String passwordHash, Instant createdAt);

    /** Changes the password only when the caller still holds the expected account version. */
    boolean updatePassword(
            UUID id, long expectedVersion, String passwordHash, Instant passwordChangedAt);
}
