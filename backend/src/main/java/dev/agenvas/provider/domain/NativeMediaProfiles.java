package dev.agenvas.provider.domain;

import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** One fixed catalog and draft interface for independent native media protocols. */
public final class NativeMediaProfiles {
    private static final List<NativeMediaProtocol> PROTOCOLS = List.of(new WanModels(), new SeedreamModels(), new GoogleVideoModels());
    private NativeMediaProfiles() {}
    public record Selection(NativeMediaProtocol protocol, NativeMediaProtocol.Profile profile) {
        public List<CreatorInputField> inputs() { return protocol.inputs(profile); }
        public MediaAdapterRegistry.Declaration declaration() { return protocol.declaration(profile); }
        public Integer resultCount(JsonNode values) { return protocol.resultCount(profile, values); }
    }
    public static boolean supports(String adapterId) { return PROTOCOLS.stream().anyMatch(p -> p.adapterIds().contains(adapterId)); }
    public static List<Selection> profiles() {
        return PROTOCOLS.stream().flatMap(protocol -> protocol.profiles().stream().map(profile -> new Selection(protocol, profile))).toList();
    }
    public static Selection resolve(String adapterId, JsonNode settings) {
        if (settings == null || !settings.isObject()) throw invalid();
        for (String key : List.of("model", "operation"))
            if (settings.hasNonNull(key) && !settings.path(key).isTextual()) throw invalid();
        String operation = settings.path("operation").asText("GENERATE");
        String model = settings.path("model").asText("").trim();
        return profiles().stream().filter(p -> p.profile().adapterId().equals(adapterId)
                && p.profile().operation().equals(operation) && (model.isEmpty() || p.profile().model().equals(model)))
                .findFirst().orElseThrow(NativeMediaProfiles::invalid);
    }
    public static ObjectNode normalizeSettings(String adapterId, JsonNode source, ObjectMapper mapper) {
        Selection selected = resolve(adapterId, source);
        Set<String> allowed = new HashSet<>(MediaCapabilityConfiguration.FIELDS);
        allowed.addAll(Set.of("model", "operation", "nativeInputs", "nativeReusableSlots"));
        if (!allowed.containsAll(source.propertyNames())) throw invalid();
        selected.protocol().validateSettings(selected.profile(), source);
        ObjectNode target = (ObjectNode) source.deepCopy();
        target.put("model", selected.profile().model()).put("operation", selected.profile().operation());
        if (source.has("defaultParameters") && !source.path("defaultParameters").isObject()) throw invalid();
        ObjectNode parameters = source.path("defaultParameters").isObject()
                ? (ObjectNode) source.path("defaultParameters").deepCopy() : mapper.createObjectNode();
        if (!Set.of(NativeMediaProtocol.VALUES_PROPERTY).containsAll(parameters.propertyNames())) throw invalid();
        JsonNode supplied = parameters.path(NativeMediaProtocol.VALUES_PROPERTY);
        if (!supplied.isMissingNode() && !supplied.isObject()) throw invalid();
        ObjectNode values = supplied.isObject() ? (ObjectNode) supplied.deepCopy() : mapper.createObjectNode();
        for (String key : values.propertyNames()) {
            CreatorInputField field = selected.inputs().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow(NativeMediaProfiles::invalid);
            if (field.media() || field.effectiveSource() != RunningHubDefinition.Source.PARAMETER) throw invalid();
        }
        for (CreatorInputField field : selected.inputs()) if (field.defaultValue() != null
                && field.effectiveSource() == RunningHubDefinition.Source.PARAMETER && !values.has(field.key()))
            values.set(field.key(), field.defaultValue().deepCopy());
        validateValues(selected, values, false);
        parameters.set(NativeMediaProtocol.VALUES_PROPERTY, values);
        target.set("defaultParameters", parameters);
        target.set("nativeInputs", mapper.valueToTree(selected.inputs()));
        target.set("nativeReusableSlots", mapper.valueToTree(selected.protocol().reusableSlots(selected.profile()).stream().sorted().toList()));
        if (source.has("defaultDurationSeconds")) {
            var duration = selected.inputs().stream().filter(f -> f.effectiveSource() == RunningHubDefinition.Source.DURATION_SECONDS).findFirst().orElseThrow(NativeMediaProfiles::invalid);
            validateField(duration, source.path("defaultDurationSeconds"));
        }
        if (selected.inputs().stream().noneMatch(f -> f.effectiveSource() == RunningHubDefinition.Source.DURATION_SECONDS)
                && (source.has("minimumSeconds") || source.has("maximumSeconds"))) throw invalid();
        return target;
    }
    public static ObjectNode effectiveValues(Selection selected, JsonNode parameters, String prompt, Integer duration, ObjectMapper mapper) {
        if (parameters == null || !parameters.isObject()
                || !Set.of(NativeMediaProtocol.VALUES_PROPERTY).containsAll(parameters.propertyNames())) throw invalid();
        JsonNode supplied = parameters.path(NativeMediaProtocol.VALUES_PROPERTY);
        if (!supplied.isMissingNode() && !supplied.isObject()) throw invalid();
        ObjectNode values = supplied.isObject() ? (ObjectNode) supplied.deepCopy() : mapper.createObjectNode();
        for (CreatorInputField field : selected.inputs()) {
            if (field.effectiveSource() == RunningHubDefinition.Source.PROMPT) {
                if (prompt == null) values.remove(field.key()); else values.put(field.key(), prompt);
            } else if (field.effectiveSource() == RunningHubDefinition.Source.DURATION_SECONDS) {
                if (duration != null) values.put(field.key(), duration);
                else if (field.defaultValue() != null) values.set(field.key(), field.defaultValue());
                else values.remove(field.key());
            } else if (!values.has(field.key()) && field.defaultValue() != null) values.set(field.key(), field.defaultValue().deepCopy());
        }
        validateValues(selected, values, false);
        return values;
    }
    public static void validateValues(Selection selected, JsonNode values, boolean complete) {
        if (values == null || !values.isObject()) throw invalid();
        Set<String> keys = new HashSet<>(); selected.inputs().forEach(f -> keys.add(f.key()));
        if (!keys.containsAll(values.propertyNames())) throw invalid();
        for (CreatorInputField field : selected.inputs()) {
            JsonNode value = values.path(field.key());
            boolean active = field.enabledWhen() == null || field.enabledWhen().value().equals(values.path(field.enabledWhen().field()));
            if (!present(values, field.key())) {
                if (complete && active && field.required()) throw invalid();
                continue;
            }
            // Preserve source drafts across model switches; generation applies native bounds.
            if (!complete && field.effectiveSource() == RunningHubDefinition.Source.PROMPT) {
                if (!value.isTextual()) throw invalid();
            } else if ((!complete || !active) && field.effectiveSource() == RunningHubDefinition.Source.DURATION_SECONDS) {
                if (!value.isIntegralNumber()) throw invalid();
            } else validateField(field, value);
        }
        selected.protocol().validate(selected.profile(), values, complete);
    }
    private static void validateField(CreatorInputField field, JsonNode value) {
        switch (field.type()) {
            case IMAGE, VIDEO, AUDIO -> { if (!value.isTextual()) throw invalid(); uuid(value.asText()); }
            case BOOLEAN -> { if (!value.isBoolean()) throw invalid(); }
            case NUMBER, INTEGER -> {
                if (!value.isNumber() || field.type() == RunningHubDefinition.FieldType.INTEGER && !value.isIntegralNumber()) throw invalid();
                if (field.minimum() != null && value.decimalValue().compareTo(field.minimum()) < 0
                        || field.maximum() != null && value.decimalValue().compareTo(field.maximum()) > 0) throw invalid();
            }
            case SELECT -> { if (field.options() == null || field.options().stream().noneMatch(o -> o.value().equals(value))) throw invalid(); }
            case STRING -> { if (!value.isTextual() || field.maxLength() != null
                    && value.asText().codePointCount(0, value.asText().length()) > field.maxLength()) throw invalid(); }
        }
    }
    public static void validateDraft(Selection selected, JsonNode parameters, String prompt, Integer duration,
            List<MediaDraft.MediaInput> media, boolean complete, ObjectMapper mapper) {
        ObjectNode values = effectiveValues(selected, parameters, prompt, duration, mapper);
        validateValues(selected, values, complete);
        java.util.Map<UUID, String> assigned = new java.util.HashMap<>();
        for (CreatorInputField field : selected.inputs()) if (field.media() && present(values, field.key())) {
            UUID id = uuid(values.path(field.key()).asText());
            var role = switch (field.type()) {
                case VIDEO -> MediaDraft.InputRole.VIDEO_REFERENCE;
                case AUDIO -> MediaDraft.InputRole.AUDIO_REFERENCE;
                default -> MediaDraft.InputRole.REFERENCE;
            };
            String previous = assigned.putIfAbsent(id, field.key());
            if (previous != null && !canReuse(selected, previous, field.key())
                    || media.stream().noneMatch(input -> input.versionId().equals(id) && input.role() == role)) throw invalid();
        }
        if (complete && media.stream().anyMatch(input -> !assigned.containsKey(input.versionId()))) throw invalid();
    }
    public static boolean canReuse(Selection selected, String firstSlot, String secondSlot) {
        var reusable = selected.protocol().reusableSlots(selected.profile());
        return !firstSlot.equals(secondSlot) && reusable.contains(firstSlot) && reusable.contains(secondSlot);
    }
    public static boolean present(JsonNode values, String key) {
        return values.hasNonNull(key) && !(values.path(key).isTextual() && values.path(key).asText().isBlank());
    }
    private static UUID uuid(String value) { try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { throw invalid(); } }
    public static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "PROVIDER_UNSUPPORTED_INPUT",
                ApiMessage.of("api.native-media.invalid-title"), ApiMessage.of("api.native-media.invalid"), false);
    }
}
