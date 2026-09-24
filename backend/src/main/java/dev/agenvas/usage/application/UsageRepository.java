package dev.agenvas.usage.application;

import dev.agenvas.usage.domain.UsageEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable idempotency boundary for append-only usage records. */
public interface UsageRepository {

    /** Inserts only the first record for an operation key. */
    boolean insertOnce(UsageEntry entry);

    /** Allows a replay to prove it matches the original operation, not hide a conflict. */
    Optional<UsageEntry> findByOperationKey(String operationKey);

    /** Lists owned project records in creation order. */
    List<UsageEntry> listProject(UUID projectId);
}
