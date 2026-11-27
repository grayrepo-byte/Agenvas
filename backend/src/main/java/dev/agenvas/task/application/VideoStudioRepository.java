package dev.agenvas.task.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface VideoStudioRepository {
    record Generation(UUID id, UUID ownerId, UUID projectId, UUID taskId, String commandKey, String requestHash,
            JsonNode request, Instant createdAt) {
    }

    void create(Generation generation);

    Optional<Generation> find(UUID owner, UUID id);

    Optional<Generation> findByCommand(UUID owner, String commandKey);

    List<Generation> list(UUID owner, Generation before, int limit);
}
