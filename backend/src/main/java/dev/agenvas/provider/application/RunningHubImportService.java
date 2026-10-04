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
    private static final Set<String> SENSITIVE = Set.of("apikey", "authorization", "accesspassword", "password", "token", "secret");
    private final JooqMediaCapabilityRepository repository;
    private final CredentialCipher cipher;
    private final RunningHubClient client;
    private final ObjectMapper mapper;
    public RunningHubImportService(JooqMediaCapabilityRepository repository, CredentialCipher cipher, RunningHubClient client, ObjectMapper mapper) {
        this.repository = repository; this.cipher = cipher; this.client = client; this.mapper = mapper;
    }
    public record Preview(RunningHubDefinition definition, List<ApiMessage> warnings) {}

    public Preview preview(UUID connectionId, RunningHubDefinition.TargetType type, String targetId, Task.Kind kind, JsonNode source) {
        if (type == null || targetId == null || !targetId.matches("[0-9]{1,32}")
                || !Set.of(Task.Kind.IMAGE_GENERATION, Task.Kind.VIDEO_GENERATION, Task.Kind.AUDIO_GENERATION).contains(kind))
            throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.requires-real-target-id-and-primary-output-type-api-documentation"));
        var connection = repository.connection(connectionId).orElseThrow(() -> RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.connection-does-not-exist")));
        if (connection.platform() != MediaPlatform.RUNNINGHUB || !connection.enabled()) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.please-select-an-enabled-runninghub-connection"));
        if (source == null || source.isNull()) {
            var version = repository.connectionVersion(connectionId, connection.currentVersion()).orElseThrow();
            String key = cipher.decryptMedia(connectionId, version.version(), new CredentialCipher.Encrypted(version.credentialCiphertext(), version.credentialNonce(), version.credentialKeyVersion()));
            try { source = client.metadata(version.origin(), key, type, targetId); }
            catch (RuntimeException unavailable) {
                throw new ApiProblemException(HttpStatus.BAD_GATEWAY, "RUNNINGHUB_DISCOVERY_UNAVAILABLE", ApiMessage.of("api.running-hub-import-service.parameter-found-not-available"),
                        ApiMessage.of("api.running-hub-import-service.please-check-the-target-id-and-permissions-or-import-the"), true);
            }
            // Only the input contract is retained. Upstream demos may also include curl and credentials.
            if (type == RunningHubDefinition.TargetType.AI_APP) source = source.path("nodeInfoList");
        }
        return candidates(type, targetId, kind, source);
    }

    Preview candidates(RunningHubDefinition.TargetType type, String targetId, Task.Kind kind, JsonNode source) {
        if (source == null || source.toString().getBytes(StandardCharsets.UTF_8).length > RunningHubDefinition.MAX_DEFINITION_BYTES)
            throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.importing-json-exceeds-size-limit"));
        rejectCredentials(source);
        if (source.path("data").has("prompt")) source = source.path("data").path("prompt");
        if (source.isTextual()) {
            try { source = mapper.readTree(source.asText()); }
            catch (RuntimeException invalid) { throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.api-format-json-string-is-invalid")); }
        }
        if (source.has("data")) source = source.path("data");
        if (source.has("nodeInfoList")) source = source.path("nodeInfoList");
        rejectCredentials(source);
        List<RunningHubDefinition.Field> fields = new ArrayList<>();
        List<ApiMessage> warnings = new ArrayList<>();
        warnings.add(ApiMessage.of("api.running-hub-import-service.the-imported-results-are-candidate-fields-please-confirm-the-open"));
        if (type == RunningHubDefinition.TargetType.WORKFLOW) {
            if (!source.isObject()) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.workflow-requires-comfyui-api-format-object"));
            for (var node : source.properties()) {
                if (!node.getKey().matches("[0-9]{1,32}")) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.invalid-workflow-node-id"));
                for (var input : node.getValue().path("inputs").properties()) {
                    JsonNode value = input.getValue();
                    // Connections, arrays and nested node configurations are never exposed as editable fields.
                    if (!(value.isTextual() || value.isNumber() || value.isBoolean())) continue;
                    if (!RunningHubDefinition.bindableFieldName(input.getKey())) {
                        warnings.add(ApiMessage.of("api.running-hub-import-service.node-field-uses-an-unsupported-mapping-format-and-was-skipped", node.getKey(), input.getKey()));
                        continue;
                    }
                    String label = node.getValue().path("_meta").path("title").asText(node.getValue().path("class_type").asText("节点 " + node.getKey())) + " · " + input.getKey();
                    fields.add(field(fields.size(), node.getKey(), input.getKey(), label,
                            value.isBoolean() ? RunningHubDefinition.FieldType.BOOLEAN : value.isIntegralNumber() ? RunningHubDefinition.FieldType.INTEGER
                                    : value.isNumber() ? RunningHubDefinition.FieldType.NUMBER : RunningHubDefinition.FieldType.STRING, value, List.of(), null));
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
        if (fields.size() > RunningHubDefinition.MAX_FIELDS) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.there-are-more-than-64-fields-please-import-the-selected"));
        RunningHubDefinition definition = new RunningHubDefinition(RunningHubDefinition.SCHEMA_VERSION, RunningHubDefinition.PROTOCOL_VERSION, type, targetId,
                List.copyOf(fields), List.of(), List.of(new RunningHubDefinition.Output(null, RunningHubDefinition.OutputKind.valueOf(kind.name().replace("_GENERATION", "")), true, 1)),
                "default", false, false, null, Sha256.hex(source.toString()));
        definition.validate(kind);
        return new Preview(definition, List.copyOf(warnings));
    }
    private RunningHubDefinition.Field field(int index, String node, String name, String label, RunningHubDefinition.FieldType type, JsonNode value, List<RunningHubDefinition.Option> options, String description) {
        return new RunningHubDefinition.Field("input" + (index + 1), label, description, type, false, value, null, null, null,
                options, false, node, name, RunningHubDefinition.Source.PARAMETER, RunningHubDefinition.Encoding.NATIVE,
                RunningHubDefinition.ResourceFormat.FILE_NAME, null);
    }
    /** App descriptions identify reference slots; node names often only identify a shared class. */
    private String appLabel(JsonNode input, String fieldName) {
        String label = input.path("description").asText("").strip();
        if (label.isBlank()) label = input.path("nodeName").asText("").strip();
        if (label.isBlank()) label = fieldName;
        if (label.length() <= RunningHubDefinition.MAX_LABEL_LENGTH) return label;
        // Keep the full description separately and avoid cutting a supplementary character in half.
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
    private void rejectCredentials(JsonNode node) {
        if (node.isObject()) for (var entry : node.properties()) {
            if (SENSITIVE.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT))) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.please-remove-the-credential-field-before-importing-do-not-paste"));
            rejectCredentials(entry.getValue());
        }
        if (node.isArray()) for (JsonNode child : node) rejectCredentials(child);
    }
}
