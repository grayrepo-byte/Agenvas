package dev.agenvas.provider.domain;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Compiled vendor contracts share draft mechanics while retaining native request and combination rules. */
public interface NativeMediaProtocol {
    String PROTOCOL_ID = "NATIVE_MEDIA";
    String VALUES_PROPERTY = "dynamicValues";
    int MAX_RESULTS = 17;
    record Profile(String adapterId, String model, String operation, Task.Kind kind) {}
    Set<String> adapterIds();
    List<Profile> profiles();
    List<CreatorInputField> inputs(Profile profile);
    MediaAdapterRegistry.Declaration declaration(Profile profile);
    /** Called after common field validation, including on partial drafts. */
    void validate(Profile profile, JsonNode values, boolean complete);
    void validateMetadata(Profile profile, String slot, Asset asset, JsonNode values);
    /** Provenance comes from the immutable version record, never from editable content metadata. */
    default void validateSource(Profile profile, String slot, dev.agenvas.artifact.domain.ArtifactVersion version) {}
    /** Only declared frame roles may reuse one exact authorized version. */
    default Set<String> reusableSlots(Profile profile) { return Set.of(); }
    /** Null means an unknown result count, settled from actual archived outputs. */
    Integer resultCount(Profile profile, JsonNode values);
    default String renderMention(Profile profile, JsonNode values, String slot) { return "@" + slot; }
    default String resolutionTier(Profile profile, JsonNode values) {
        String value = values.path("resolution").asText("");
        return value.isEmpty() ? null : "4k".equalsIgnoreCase(value) ? "2160p" : value;
    }
    default void validateSettings(Profile profile, JsonNode settings) {}
    /** Inline data is used unless the native API requires an object URL. */
    default boolean usesMediaUrl(Profile profile, CreatorInputField field) {
        return field.type() == RunningHubDefinition.FieldType.VIDEO || field.type() == RunningHubDefinition.FieldType.AUDIO;
    }
    default void validateProbe(Profile profile, String slot, JsonNode values, JsonNode probe) {}
    /** Combination checks run before any relay upload, after every original has been verified. */
    default void validatePrepared(Profile profile, JsonNode values, java.util.Map<String, Asset> inputs) {}
    default java.math.BigDecimal billableInputSeconds(Profile profile, JsonNode values, java.util.Map<String, Asset> inputs) {
        return java.math.BigDecimal.ZERO;
    }
}
