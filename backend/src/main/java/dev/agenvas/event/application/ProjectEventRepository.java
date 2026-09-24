package dev.agenvas.event.application;

import dev.agenvas.event.domain.ProjectEvent;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/** Persistence boundary for project-local sequence locking and immutable event rows. */
public interface ProjectEventRepository {

    /** Locks the owned project counter for the duration of the caller transaction. */
    OptionalLong lockCurrentSequence(UUID ownerId, UUID projectId);

    /** Advances the already locked counter exactly once. */
    boolean advanceSequence(UUID ownerId, UUID projectId, long expectedSequence, long nextSequence);

    /** Inserts the event paired with the counter advance in the same transaction. */
    void insert(ProjectEvent event);

    /** Reads a bounded ordered replay page after an exclusive cursor. */
    List<ProjectEvent> listAfter(UUID ownerId, UUID projectId, long afterSequence, int limit);

    /** Reads latest and oldest retained sequence for cursor validation. */
    CursorBounds cursorBounds(UUID ownerId, UUID projectId);

    /** Deletes at most limit expired events; project event_seq never moves backward. */
    int pruneOlderThan(java.time.Instant cutoff, int limit);

    /** Waterline and retained log floor, or null when the project is not owned. */
    record CursorBounds(long latestSequence, Long oldestRetainedSequence) {}
}
