package dev.agenvas.provider.application;

import dev.agenvas.task.domain.Task;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Typed reader for the single authoritative ordered mediaInput snapshot on a Task. */
final class FrozenMediaInputs {
    private FrozenMediaInputs() {}

    static List<Image> images(Task task) {
        return read(task, "images");
    }

    static List<Image> audios(Task task) {
        if (!task.input().path("mediaInput").has("audios")) return List.of();
        return read(task, "audios");
    }

    static List<Image> videos(Task task) {
        if (!task.input().path("mediaInput").has("videos")) return List.of();
        return read(task, "videos");
    }

    private static List<Image> read(Task task, String field) {
        JsonNode images = task.input().path("mediaInput").path(field);
        if (!images.isArray()) {
            throw new IllegalArgumentException("Frozen media input images are invalid");
        }
        List<Image> result = new ArrayList<>();
        Set<UUID> versions = new HashSet<>();
        for (int index = 0; index < images.size(); index++) {
            JsonNode image = images.path(index);
            UUID versionId = UUID.fromString(image.path("versionId").asText());
            if (image.path("order").asInt(-1) != index || !versions.add(versionId)) {
                throw new IllegalArgumentException(
                        "Frozen media input order or version identity is invalid");
            }
            result.add(new Image(UUID.fromString(image.path("artifactId").asText()),
                    versionId, image.path("role").asText(), index));
        }
        return List.copyOf(result);
    }

    static Image first(Task task) {
        return images(task).stream().findFirst().orElseThrow(() ->
                new IllegalArgumentException("Frozen media input has no image"));
    }

    record Image(UUID artifactId, UUID versionId, String role, int order) {}
}
