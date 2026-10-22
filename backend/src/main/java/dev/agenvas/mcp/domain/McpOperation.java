package dev.agenvas.mcp.domain;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** One committed delegated write. The user command key survives token rotation. */
public record McpOperation(UUID id, UUID userId, UUID tokenId, UUID projectId,
        String commandId, String toolName, String requestHash, JsonNode result, Instant createdAt) {}
