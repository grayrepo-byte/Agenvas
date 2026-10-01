package dev.agenvas.provider.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Persist before downloading. Ordinals remain fixed even when later queries reorder results. */
public record ProviderResultManifest(int schemaVersion, List<Result> results, JsonNode usage) {
    public static final int SCHEMA_VERSION = 1;
    public record Result(int ordinal, String nodeId, RunningHubDefinition.OutputKind kind,
            boolean primary, String url) {}
}
