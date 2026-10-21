package dev.agenvas.run.domain;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Frozen canvas authorization. Absence preserves the narrower historical binding protocol. */
public final class RunCanvasScope {
    public static final int VERSION = 1;
    private RunCanvasScope() {}

    public static boolean enabled(JsonNode snapshot) {
        return snapshot != null && snapshot.path("canvasScope").path("version").asInt() == VERSION
                && snapshot.path("canvasScope").path("items").isArray();
    }

    public static JsonNode items(JsonNode snapshot) {
        if (!enabled(snapshot)) throw new IllegalStateException("Frozen canvas scope is unavailable");
        return snapshot.path("canvasScope").path("items");
    }

    public static boolean containsItem(JsonNode snapshot, UUID itemId) {
        if (!enabled(snapshot)) return false;
        for (JsonNode item : items(snapshot)) {
            if (itemId.toString().equals(item.path("itemId").asText())) return true;
        }
        return false;
    }

    public static boolean containsVersion(JsonNode snapshot, UUID artifactId, UUID versionId) {
        if (!enabled(snapshot) || versionId == null) return false;
        for (JsonNode item : items(snapshot)) {
            if (artifactId.toString().equals(item.path("artifactId").asText())
                    && versionId.toString().equals(item.path("selectedVersionId").asText())) return true;
        }
        return false;
    }

    /** Only the exact text revision observed when accepting the Run may be replaced. */
    public static boolean unchangedText(JsonNode snapshot, UUID artifactId, UUID versionId, long version) {
        if (!containsVersion(snapshot, artifactId, versionId)) return false;
        for (JsonNode item : items(snapshot)) {
            if (artifactId.toString().equals(item.path("artifactId").asText())
                    && versionId.toString().equals(item.path("selectedVersionId").asText())
                    && item.path("artifactVersion").canConvertToLong()
                    && version == item.path("artifactVersion").longValue()) return true;
        }
        return false;
    }
}
