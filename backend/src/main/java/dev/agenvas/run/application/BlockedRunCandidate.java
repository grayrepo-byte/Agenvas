package dev.agenvas.run.application;

import java.util.UUID;

/** Server-owned identity for recovery; state and ownership are rechecked under the project lock. */
public record BlockedRunCandidate(UUID ownerId, UUID projectId, UUID runId) {}
