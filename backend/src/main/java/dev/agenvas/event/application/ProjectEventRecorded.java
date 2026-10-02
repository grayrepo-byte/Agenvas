package dev.agenvas.event.application;

import dev.agenvas.event.domain.ProjectEvent;
import java.util.UUID;

/** Transaction-local coordination signal. Its durable event and business state remain authoritative. */
public record ProjectEventRecorded(UUID ownerId, ProjectEvent event) {}
