package dev.agenvas.canvas.application;

import dev.agenvas.canvas.domain.CanvasItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Owner-scoped persistence boundary for canvas presentation records. */
public interface CanvasItemRepository {

    /** Lists the persisted layout in deterministic z-order. */
    List<CanvasItem> list(UUID ownerId, UUID projectId);

    /** Locks one nested item for an optimistic command. */
    Optional<CanvasItem> findForUpdate(UUID ownerId, UUID projectId, UUID itemId);

    /** Inserts a client-identified presentation item. */
    boolean create(CanvasItem item);

    /** Replaces mutable layout fields when the expected version matches. */
    boolean update(UUID ownerId, CanvasItem item, long expectedVersion, Instant updatedAt);

    /** Removes presentation only, leaving the subject untouched. */
    boolean delete(UUID ownerId, UUID projectId, UUID itemId, long expectedVersion);
}
