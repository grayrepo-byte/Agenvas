package dev.agenvas.canvas.application;

import dev.agenvas.canvas.domain.CanvasConnection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for editable input topology and removable generated-result lineage. */
public interface CanvasConnectionRepository {
    void create(CanvasConnection connection);
    Optional<CanvasConnection> find(UUID ownerId, UUID projectId, UUID connectionId);
    List<CanvasConnection> list(UUID ownerId, UUID projectId);
    boolean delete(UUID projectId, UUID connectionId);
}
