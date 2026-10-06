package dev.agenvas.provider.application;

import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.RunningHubClient;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Read-only candidate discovery. It cannot publish a capability or submit generation. */
@Service
public final class RunningHubImportService {
    private final JooqMediaCapabilityRepository repository;
    private final CredentialCipher cipher;
    private final RunningHubClient client;
    private final ObjectMapper mapper;
    public RunningHubImportService(JooqMediaCapabilityRepository repository, CredentialCipher cipher, RunningHubClient client, ObjectMapper mapper) {
        this.repository = repository; this.cipher = cipher; this.client = client; this.mapper = mapper;
    }
    public record Preview(RunningHubDefinition definition, List<ApiMessage> warnings, List<String> recommendedFieldKeys, String targetName) {}

    public Preview preview(UUID connectionId, RunningHubDefinition.TargetType type, String targetId, Task.Kind kind, JsonNode source) {
        if (type == null || targetId == null || !targetId.matches("[0-9]{1,32}")
                || !Set.of(Task.Kind.IMAGE_GENERATION, Task.Kind.VIDEO_GENERATION, Task.Kind.AUDIO_GENERATION).contains(kind))
            throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.requires-real-target-id-and-primary-output-type-api-documentation"));
        var connection = repository.connection(connectionId).orElseThrow(() -> RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.connection-does-not-exist")));
        if (connection.platform() != MediaPlatform.RUNNINGHUB || !connection.enabled()) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.please-select-an-enabled-runninghub-connection"));
        String targetName = null;
        if (source == null || source.isNull()) {
            var version = repository.connectionVersion(connectionId, connection.currentVersion()).orElseThrow();
            String key = cipher.decryptMedia(connectionId, version.version(), new CredentialCipher.Encrypted(version.credentialCiphertext(), version.credentialNonce(), version.credentialKeyVersion()));
            try {
                var metadata = client.metadata(version.origin(), key, type, targetId);
                source = metadata.source();
                targetName = metadata.targetName();
            }
            catch (RuntimeException unavailable) {
                throw new ApiProblemException(HttpStatus.BAD_GATEWAY, "RUNNINGHUB_DISCOVERY_UNAVAILABLE", ApiMessage.of("api.running-hub-import-service.parameter-found-not-available"),
                        ApiMessage.of("api.running-hub-import-service.please-check-the-target-id-and-permissions-or-import-the"), true);
            }
            // Only the input contract is retained; the optional name stays outside importSource.
            if (type == RunningHubDefinition.TargetType.AI_APP) source = source.path("nodeInfoList");
        }
        var preview = candidates(type, targetId, kind, source);
        return new Preview(preview.definition(), preview.warnings(), preview.recommendedFieldKeys(), targetName);
    }

    Preview candidates(RunningHubDefinition.TargetType type, String targetId, Task.Kind kind, JsonNode source) {
        if (source == null || source.toString().getBytes(StandardCharsets.UTF_8).length > RunningHubDefinition.MAX_IMPORT_SOURCE_BYTES)
            throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.importing-json-exceeds-size-limit"));
        RunningHubDefinition.rejectImportCredentials(source);
        if (source.path("data").has("prompt")) source = source.path("data").path("prompt");
        if (source.isTextual()) {
            try { source = mapper.readTree(source.asText()); }
            catch (RuntimeException invalid) { throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.api-format-json-string-is-invalid")); }
        }
        if (source.has("data")) source = source.path("data");
        if (source.has("nodeInfoList")) source = source.path("nodeInfoList");
        RunningHubDefinition.rejectImportCredentials(source);
        List<RunningHubDefinition.Field> fields = new ArrayList<>();
        List<RunningHubInputDiscovery.PromptRole> promptPriorities = new ArrayList<>();
        List<RunningHubDefinition.NodeOption> nodeOptions = new ArrayList<>();
        List<ApiMessage> warnings = new ArrayList<>();
        warnings.add(ApiMessage.of("api.running-hub-import-service.the-imported-results-are-candidate-fields-please-confirm-the-open"));
        if (type == RunningHubDefinition.TargetType.WORKFLOW) {
            if (!source.isObject()) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.workflow-requires-comfyui-api-format-object"));
            Set<String> negativeNodes = RunningHubInputDiscovery.conditioningAncestors(source, true);
            Set<String> positiveNodes = RunningHubInputDiscovery.conditioningAncestors(source, false);
            for (var node : source.properties()) {
                if (!node.getKey().matches("[0-9]{1,32}")) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.invalid-workflow-node-id"));
                // Preserve names only, never the graph or connected inputs, for the output picker.
                String title = node.getValue().path("_meta").path("title").asText("").strip();
                if (title.isBlank()) title = node.getValue().path("class_type").asText("").strip();
                if (title.isBlank()) title = "节点 " + node.getKey();
                nodeOptions.add(new RunningHubDefinition.NodeOption(node.getKey(), boundedLabel(title)));
                for (var input : node.getValue().path("inputs").properties()) {
                    JsonNode value = input.getValue();
                    // Connections, arrays and nested node configurations are never exposed as editable fields.
                    if (!(value.isTextual() || value.isNumber() || value.isBoolean())) continue;
                    if (!RunningHubDefinition.bindableFieldName(input.getKey())) {
                        warnings.add(ApiMessage.of("api.running-hub-import-service.node-field-uses-an-unsupported-mapping-format-and-was-skipped", node.getKey(), input.getKey()));
                        continue;
                    }
                    String label = node.getValue().path("_meta").path("title").asText(node.getValue().path("class_type").asText("节点 " + node.getKey())) + " · " + input.getKey();
                    var declaredType = value.isBoolean() ? RunningHubDefinition.FieldType.BOOLEAN : value.isIntegralNumber() ? RunningHubDefinition.FieldType.INTEGER
                            : value.isNumber() ? RunningHubDefinition.FieldType.NUMBER : RunningHubDefinition.FieldType.STRING;
                    var hint = RunningHubInputDiscovery.hint(declaredType, input.getKey(), node.getValue().path("class_type").asText(""), title);
                    var priority = hint.promptPriority();
                    if (negativeNodes.contains(node.getKey())) priority = RunningHubInputDiscovery.PromptRole.NONE;
                    else if (priority != RunningHubInputDiscovery.PromptRole.NONE && positiveNodes.contains(node.getKey())) priority = RunningHubInputDiscovery.PromptRole.POSITIVE_CONDITIONING;
                    promptPriorities.add(priority);
                    fields.add(field(fields.size(), node.getKey(), input.getKey(), label, hint.type(), value, List.of(), null));
                }
            }
            warnings.add(ApiMessage.of("api.running-hub-import-service.workflow-json-does-not-contain-complete-field-rules-internal-parameters"));
        } else {
            if (!source.isArray()) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.ai-application-requires-nodeinfolist-array"));
            for (JsonNode input : source) {
                String node = input.path("nodeId").asText();
                String name = input.path("fieldName").asText();
                String typeName = input.path("fieldType").asText("STRING").toUpperCase(java.util.Locale.ROOT);
                var fieldType = switch (typeName) {
                    case "IMAGE" -> RunningHubDefinition.FieldType.IMAGE; case "AUDIO" -> RunningHubDefinition.FieldType.AUDIO;
                    case "VIDEO" -> RunningHubDefinition.FieldType.VIDEO; case "NUMBER", "FLOAT" -> RunningHubDefinition.FieldType.NUMBER;
                    case "INTEGER", "INT" -> RunningHubDefinition.FieldType.INTEGER; case "BOOLEAN", "BOOL" -> RunningHubDefinition.FieldType.BOOLEAN;
                    case "LIST" -> RunningHubDefinition.FieldType.SELECT; default -> RunningHubDefinition.FieldType.STRING;
                };
                JsonNode value = input.get("fieldValue");
                var options = options(input.get("fieldData"));
                if (fieldType == RunningHubDefinition.FieldType.SELECT && options.isEmpty()) {
                    fieldType = RunningHubDefinition.FieldType.STRING;
                    warnings.add(ApiMessage.of("api.running-hub-import-service.node-field-has-no-recognized-list-options-add-dropdown-options", node, name));
                }
                var hint = RunningHubInputDiscovery.hint(fieldType, name, input.path("nodeName").asText(""), appLabel(input, name));
                fieldType = hint.type();
                promptPriorities.add(hint.promptPriority());
                if (Set.of(RunningHubDefinition.FieldType.IMAGE, RunningHubDefinition.FieldType.AUDIO, RunningHubDefinition.FieldType.VIDEO).contains(fieldType)) value = null;
                else if (value != null && fieldType == RunningHubDefinition.FieldType.STRING) value = mapper.valueToTree(value.asText());
                else if (value != null && value.isTextual() && Set.of(RunningHubDefinition.FieldType.INTEGER, RunningHubDefinition.FieldType.NUMBER, RunningHubDefinition.FieldType.BOOLEAN).contains(fieldType)) {
                    try { value = mapper.readTree(value.asText()); } catch (RuntimeException invalid) { value = null; }
                }
                if (fieldType == RunningHubDefinition.FieldType.SELECT && value != null) {
                    JsonNode selected = value;
                    if (options.stream().noneMatch(option -> RunningHubDefinition.scalarEquals(option.value(), selected))) value = null;
                }
                fields.add(field(fields.size(), node, name, appLabel(input, name), fieldType, value, options, input.path("description").asText(null)));
            }
        }
        int primaryPrompt = RunningHubInputDiscovery.primaryPrompt(promptPriorities);
        if (primaryPrompt >= 0) {
            var prompt = fields.get(primaryPrompt);
            fields.set(primaryPrompt, new RunningHubDefinition.Field(prompt.key(), prompt.label(), prompt.description(), prompt.type(),
                    prompt.required(), prompt.defaultValue(), prompt.minimum(), prompt.maximum(), prompt.maxLength(), prompt.options(),
                    prompt.advanced(), prompt.nodeId(), prompt.fieldName(), RunningHubDefinition.Source.PROMPT,
                    prompt.encoding(), prompt.resourceFormat(), prompt.enabledWhen()));
        }
        if (fields.size() > RunningHubDefinition.MAX_FIELDS) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.there-are-more-than-64-fields-please-import-the-selected"));
        RunningHubDefinition definition = new RunningHubDefinition(RunningHubDefinition.SCHEMA_VERSION, RunningHubDefinition.PROTOCOL_VERSION, type, targetId,
                List.copyOf(fields), List.of(), List.of(new RunningHubDefinition.Output(null, RunningHubDefinition.OutputKind.valueOf(kind.name().replace("_GENERATION", "")), true, 1)),
                "default", false, false, null, Sha256.hex(source.toString()),
                type == RunningHubDefinition.TargetType.WORKFLOW ? List.copyOf(nodeOptions) : null, source.deepCopy());
        definition.validate(kind);
        var recommended = fields.stream().filter(field -> field.media() || field.effectiveSource() == RunningHubDefinition.Source.PROMPT)
                .map(RunningHubDefinition.Field::key).toList();
        return new Preview(definition, List.copyOf(warnings), recommended, null);
    }
    private RunningHubDefinition.Field field(int index, String node, String name, String label, RunningHubDefinition.FieldType type, JsonNode value, List<RunningHubDefinition.Option> options, String description) {
        boolean media = Set.of(RunningHubDefinition.FieldType.IMAGE, RunningHubDefinition.FieldType.AUDIO, RunningHubDefinition.FieldType.VIDEO).contains(type);
        return new RunningHubDefinition.Field("input" + (index + 1), label, description, type, false, media ? null : value, null, null, null,
                options, false, node, name, RunningHubDefinition.Source.PARAMETER, RunningHubDefinition.Encoding.NATIVE,
                media && RunningHubInputDiscovery.urlInput(name) ? RunningHubDefinition.ResourceFormat.URL : RunningHubDefinition.ResourceFormat.FILE_NAME, null);
    }
    /** App descriptions identify reference slots; node names often only identify a shared class. */
    private String appLabel(JsonNode input, String fieldName) {
        String label = input.path("description").asText("").strip();
        if (label.isBlank()) label = input.path("nodeName").asText("").strip();
        if (label.isBlank()) label = fieldName;
        return boundedLabel(label);
    }
    private String boundedLabel(String label) {
        if (label.length() <= RunningHubDefinition.MAX_LABEL_LENGTH) return label;
        // Avoid cutting a supplementary character in half at the shared display-label limit.
        int end = RunningHubDefinition.MAX_LABEL_LENGTH;
        if (Character.isHighSurrogate(label.charAt(end - 1))) end--;
        return label.substring(0, end);
    }
    private List<RunningHubDefinition.Option> options(JsonNode data) {
        if (data == null || data.isNull()) return List.of();
        if (data.isTextual()) {
            try { data = mapper.readTree(data.asText()); } catch (RuntimeException invalid) { return List.of(); }
        }
        if (data.isObject()) data = data.path("options");
        // Some AI apps export enum widgets as ["COMBO", {"options": [...]}].
        if (data.isArray() && data.size() == 2 && data.get(0).isTextual()
                && "COMBO".equals(data.get(0).asText()) && data.get(1).isObject()) data = data.get(1).path("options");
        // ComfyUI exports enum widgets as [allowedValues, widgetSettings].
        if (data.isArray() && data.size() == 2 && data.get(0).isArray() && data.get(1).isObject()) data = data.get(0);
        if (!data.isArray() || data.size() > RunningHubDefinition.MAX_OPTIONS) return List.of();
        List<RunningHubDefinition.Option> options = new ArrayList<>();
        for (JsonNode option : data) {
            JsonNode value = option.isObject() ? option.get("value") : option;
            if (value == null || !(value.isTextual() || value.isNumber() || value.isBoolean())) return List.of();
            options.add(new RunningHubDefinition.Option(option.isObject() ? option.path("label").asText(value.asText()) : value.asText(), value));
        }
        return List.copyOf(options);
    }
}
