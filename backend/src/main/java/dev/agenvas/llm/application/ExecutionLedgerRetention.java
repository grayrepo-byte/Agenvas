package dev.agenvas.llm.application;

import java.util.List;
import java.util.UUID;

/** Deletes execution history for terminal units locked by the retention coordinator.
 * Business tasks, runs, generated versions and assets remain intact. Joins the caller's transaction.
 */
public interface ExecutionLedgerRetention {
    void deleteFor(List<UUID> runIds);
}
