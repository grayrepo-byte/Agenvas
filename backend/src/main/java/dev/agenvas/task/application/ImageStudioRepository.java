package dev.agenvas.task.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Studio command ledger with retained history tombstones; execution state belongs only to Task. */
public interface ImageStudioRepository {
    record Batch(UUID id, UUID ownerId, UUID projectId, String commandKey, String requestHash,
            JsonNode request, Instant createdAt, Boolean deleteFiles) {
    }

    Optional<Batch> findByCommand(UUID ownerId, String commandKey);
    Optional<Batch> find(UUID ownerId, UUID batchId);
    List<Batch> list(UUID ownerId, Batch before, int limit);
    void create(Batch batch);
    void addTask(UUID projectId, UUID batchId, int ordinal, UUID taskId);
    List<UUID> taskIds(UUID batchId);
    boolean otherBatchReferencesAssets(UUID ownerId, UUID projectId, UUID batchId, Set<UUID> assetIds);
    void markDeleted(UUID ownerId, UUID batchId, Instant deletedAt, boolean deleteFiles);
}
