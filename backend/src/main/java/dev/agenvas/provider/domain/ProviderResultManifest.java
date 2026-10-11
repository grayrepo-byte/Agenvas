package dev.agenvas.provider.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Persist expanded results before archiving. Ordinals remain fixed even when later queries reorder results. */
public record ProviderResultManifest(int schemaVersion, List<Result> results, JsonNode usage) {
    public static final int SCHEMA_VERSION = 1;

    /** JSON null and an omitted optional value both mean absent, including after persistence. */
    public ProviderResultManifest {
        usage = usage != null && usage.isNull() ? null : usage;
    }

    /** ZIP members are pinned by name and bytes, independently of a later archive's ordering. */
    public record ArchiveEntry(String name, String sha256) {}
    public record Result(int ordinal, String nodeId, RunningHubDefinition.OutputKind kind,
            boolean primary, String url, ArchiveEntry archiveEntry, JsonNode providerMetadata) {
        /** Keep optional metadata equal before and after Jackson restores a persisted manifest. */
        public Result {
            providerMetadata = providerMetadata != null && providerMetadata.isNull() ? null : providerMetadata;
        }

        public Result(int ordinal, String nodeId, RunningHubDefinition.OutputKind kind, boolean primary, String url) {
            this(ordinal, nodeId, kind, primary, url, null, null);
        }
        public Result(int ordinal, String nodeId, RunningHubDefinition.OutputKind kind, boolean primary, String url, ArchiveEntry entry) {
            this(ordinal, nodeId, kind, primary, url, entry, null);
        }
    }
}
