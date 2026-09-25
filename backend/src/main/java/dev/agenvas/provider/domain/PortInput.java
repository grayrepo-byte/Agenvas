package dev.agenvas.provider.domain;

import dev.agenvas.task.domain.Task;
import tools.jackson.databind.JsonNode;

/** Provider-neutral input used to check whether a fixed adapter can serve a step. */
public record PortInput(Task.Kind kind, int durationSeconds, JsonNode input) {}
