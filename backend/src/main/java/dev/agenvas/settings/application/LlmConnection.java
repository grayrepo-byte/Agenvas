package dev.agenvas.settings.application;

import java.time.Instant;
import java.util.UUID;

/** Stable editable identity; its head points to a separate immutable execution configuration. */
public record LlmConnection(UUID id, String name, int currentConfigVersion, Instant createdAt, Instant updatedAt) {
}
