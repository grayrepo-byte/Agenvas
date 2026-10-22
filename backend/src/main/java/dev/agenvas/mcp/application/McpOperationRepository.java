package dev.agenvas.mcp.application;

import dev.agenvas.mcp.domain.McpOperation;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Transactional external command ledger, separate from internal model turns. */
public interface McpOperationRepository {
    boolean reserve(McpOperation operation);
    Optional<McpOperation> find(UUID userId, String commandId);
    void complete(UUID operationId, JsonNode result);
    void linkTask(UUID operationId, UUID taskId, int outputIndex);
    void linkVersion(UUID operationId, UUID versionId);
    Optional<UUID> operationForTask(UUID taskId);
}
