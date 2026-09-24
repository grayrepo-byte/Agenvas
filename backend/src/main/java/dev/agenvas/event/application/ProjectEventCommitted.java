package dev.agenvas.event.application;

import java.util.UUID;

/** Post-commit hint to read durable project events sooner; never carries event payload. */
public record ProjectEventCommitted(UUID projectId) {}
