package dev.agenvas.project.application;

import dev.agenvas.project.domain.Project;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary that always requires the authenticated owner id. */
public interface ProjectRepository {

    /** Inserts a new active project. */
    void create(Project project);

    /** Finds one project within the supplied owner boundary. */
    Optional<Project> findById(UUID ownerId, UUID projectId);

    /** Reads the event waterline and active Run id used to assemble one project snapshot. */
    Optional<SnapshotAnchor> findSnapshotAnchor(UUID ownerId, UUID projectId);

    /** Locks and returns the database-owned active Run slot. */
    Optional<RunSlot> lockRunSlot(UUID ownerId, UUID projectId);

    /** Assigns an empty active Run slot while the project row lock is held. */
    boolean claimRunSlot(UUID ownerId, UUID projectId, UUID runId, Instant updatedAt);

    /** Releases the slot only when it still belongs to the expected Run. */
    boolean releaseRunSlot(UUID ownerId, UUID projectId, UUID runId, Instant updatedAt);

    /** Returns one keyset page ordered newest first. */
    List<Project> list(
            UUID ownerId,
            boolean includeArchived,
            Instant beforeCreatedAt,
            UUID beforeId,
            int limit);

    /** Applies editable fields only when the optimistic version matches. */
    boolean update(
            UUID ownerId,
            UUID projectId,
            long expectedVersion,
            String name,
            Project.AspectRatio aspectRatio,
            Instant updatedAt);

    /** Archives an active project only when the optimistic version matches. */
    boolean archive(
            UUID ownerId, UUID projectId, long expectedVersion, Instant archivedAt);

    /** Minimal locked projection needed by Run slot arbitration. */
    record RunSlot(Project.Status projectStatus, UUID activeRunId) {}

    /** Project state that anchors every entity read in one repeatable-read snapshot. */
    record SnapshotAnchor(long eventSequence, UUID activeRunId) {}
}
