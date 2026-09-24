package dev.agenvas.export.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Project-scoped proposal persistence and compare-and-set decision boundary. */
public interface ExportProposalRepository {

    /** Inserts a fully validated, immutable pending proposal. */
    void create(ExportProposal proposal);

    /** Reads one proposal without exposing another project's identity. */
    Optional<ExportProposal> find(UUID projectId, UUID proposalId);

    /** Locks the exact proposal during a human decision. */
    Optional<ExportProposal> findForUpdate(UUID projectId, UUID proposalId);

    /** Lists the newest persisted proposals for a project, including decided ones. */
    List<ExportProposal> list(UUID projectId);

    /** Commits one authenticated status change after all checks and Task creation. */
    boolean decide(UUID projectId, UUID proposalId, ExportProposal.Status target,
            UUID taskId, UUID userId, Instant now);
}
