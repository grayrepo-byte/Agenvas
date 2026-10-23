package dev.agenvas.run.application;

import java.util.UUID;

/** Server-owned recovery identity; project ownership and Run state are rechecked before continuation. */
public record WaitingRunCandidate(UUID ownerId, UUID projectId, UUID runId) {}
