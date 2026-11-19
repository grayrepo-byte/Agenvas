package dev.agenvas.task.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Read-only identities from one archive row. Primary position is independent of provider ordinal. */
public record ArchivedTaskResults(Identity primary, List<Identity> additional, boolean hasAdditionalResults) {
    public ArchivedTaskResults {
        additional = List.copyOf(additional);
    }

    /** Keep validated UUID text in its original case for exact historical comparisons and wire projections. */
    public record Identity(String artifactId, String artifactVersionId, String canvasItemId, boolean hasCanvasItemId,
            Integer ordinal, Boolean selected) {
        private static Identity read(JsonNode node) {
            return new Identity(uuid(node, "artifactId"), uuid(node, "artifactVersionId"), uuid(node, "canvasItemId"),
                    node.has("canvasItemId"), node.path("ordinal").isInt() ? node.path("ordinal").intValue() : null,
                    node.path("selected").isBoolean() ? node.path("selected").booleanValue() : null);
        }

        private static String uuid(JsonNode node, String field) {
            if (!node.path(field).isString())
                return null;
            String value = node.path(field).asText();
            try {
                UUID id = UUID.fromString(value);
                return id.toString().equalsIgnoreCase(value) ? value : null;
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
    }

    public static ArchivedTaskResults read(JsonNode output) {
        if (output == null || !output.isObject())
            return new ArchivedTaskResults(null, List.of(), false);
        JsonNode extras = output.path("additionalResults");
        List<Identity> additional = new ArrayList<>();
        if (extras.isArray())
            for (JsonNode extra : extras)
                if (extra.isObject())
                    additional.add(Identity.read(extra));
        return new ArchivedTaskResults(Identity.read(output), additional, extras.isArray());
    }
}
