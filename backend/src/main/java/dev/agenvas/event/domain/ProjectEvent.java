package dev.agenvas.event.domain;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** One immutable, project-local transactional event safe for client replay. */
public record ProjectEvent(
        UUID projectId,
        long seq,
        UUID eventId,
        String type,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        JsonNode payload,
        Instant occurredAt) {}
