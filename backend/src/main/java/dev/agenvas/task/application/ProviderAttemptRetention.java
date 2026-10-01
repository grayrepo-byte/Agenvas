package dev.agenvas.task.application;

import java.util.List;
import java.util.UUID;

/** Deletes execution history for expired units stopped and locked by the retention coordinator.
 * Business tasks, runs, generated versions and assets remain intact. Joins the caller's transaction.
 */
public interface ProviderAttemptRetention {
    void deleteFor(List<UUID> taskIds);
}
