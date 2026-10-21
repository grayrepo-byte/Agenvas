package dev.agenvas.agent.domain;

import java.time.Instant;
import java.util.UUID;

/** Stable project assistant identity and settings. Canvas visibility belongs to each frozen Run. */
public record AgentInstance(
        UUID id,
        UUID projectId,
        String profileKey,
        int profileVersion,
        String name,
        String instruction,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
