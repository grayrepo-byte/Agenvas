package dev.agenvas.provider.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Persist expanded results before archiving. Ordinals remain fixed even when later queries reorder results. */
public record ProviderResultManifest(int schemaVersion, List<Result> results, JsonNode usage) {
    public static final int SCHEMA_VERSION = 1;
    /** ZIP members are pinned by name and bytes, independently of a later archive's ordering. */
    public record ArchiveEntry(String name, String sha256) {}
    public record Result(int ordinal, String nodeId, RunningHubDefinition.OutputKind kind,
            boolean primary, String url, ArchiveEntry archiveEntry) {
        public Result(int ordinal, String nodeId, RunningHubDefinition.OutputKind kind, boolean primary, String url) {
            this(ordinal, nodeId, kind, primary, url, null);
        }
    }
}
