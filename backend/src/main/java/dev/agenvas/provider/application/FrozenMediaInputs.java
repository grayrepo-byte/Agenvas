package dev.agenvas.provider.application;

import dev.agenvas.task.domain.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Typed reader for the single authoritative ordered mediaInput snapshot on a Task. */
final class FrozenMediaInputs {
    private FrozenMediaInputs() {}

    static List<Image> images(Task task) {
        List<Image> result = new ArrayList<>();
        for (JsonNode image : task.input().path("mediaInput").path("images")) {
            result.add(new Image(UUID.fromString(image.path("artifactId").asText()),
                    UUID.fromString(image.path("versionId").asText()),
                    image.path("role").asText(), image.path("order").asInt()));
        }
        return List.copyOf(result);
    }

    static Image first(Task task) {
        return images(task).stream().findFirst().orElseThrow(() ->
                new IllegalArgumentException("Frozen media input has no image"));
    }

    record Image(UUID artifactId, UUID versionId, String role, int order) {}
}
