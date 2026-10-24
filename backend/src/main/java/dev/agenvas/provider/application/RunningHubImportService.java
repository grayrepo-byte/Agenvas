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
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    public enum Confidence { DECLARED, INFERRED, UNCERTAIN }
    public enum InsightReason {
        TYPE_DECLARATION, WIDGET_TYPE, JSON_VALUE, UPLOAD_WIDGET, NAME_HINT, DIRECT_CREATIVE_CONNECTION,
        PRIMARY_PROMPT, NEGATIVE_PROMPT, AMBIGUOUS_PROMPT, CONFLICTING_MEDIA_METADATA, MISSING_ENUM_OPTIONS,
        RESOURCE_URL_NODE, RESOURCE_FILE_NODE, RESOURCE_FORMAT_UNCERTAIN, EXPLICIT_GROUP, MISSING_TYPE_METADATA
    }
    /** Bounded evidence codes for the editor, never executable expressions or imported text. */
    public record FieldInsight(String key, String nodeId, String fieldName, Confidence confidence, List<InsightReason> reasons) {}
    public record Preview(RunningHubDefinition definition, List<ApiMessage> warnings, List<String> recommendedFieldKeys,
            String targetName, List<FieldInsight> fieldInsights) {}

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
        return new Preview(preview.definition(), preview.warnings(), preview.recommendedFieldKeys(), targetName, preview.fieldInsights());
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
        List<Evidence> evidence = new ArrayList<>();
        List<RunningHubInputDiscovery.PromptRole> promptPriorities = new ArrayList<>();
        Set<String> graphRecommendations = new HashSet<>();
        Set<String> ambiguousFieldKeys = new HashSet<>();
        List<RunningHubDefinition.NodeOption> nodeOptions = new ArrayList<>();
        List<ApiMessage> warnings = new ArrayList<>();
        warnings.add(ApiMessage.of("api.running-hub-import-service.the-imported-results-are-candidate-fields-please-confirm-the-open"));
        if (type == RunningHubDefinition.TargetType.WORKFLOW) {
            if (!source.isObject()) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.workflow-requires-comfyui-api-format-object"));
            Set<String> negativeNodes = RunningHubInputDiscovery.conditioningAncestors(source, true);
            Set<String> positiveNodes = RunningHubInputDiscovery.conditioningAncestors(source, false);
            Set<String> creativeSources = RunningHubInputDiscovery.creativeSources(source);
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
                    String nodeType = node.getValue().path("class_type").asText("");
                    var declaredType = RunningHubInputDiscovery.workflowType(value, input.getKey(), nodeType);
                    boolean declared = value.isNumber() && RunningHubInputDiscovery.declaredWorkflowType(input.getKey(), nodeType);
                    var fieldEvidence = new Evidence(declared ? Confidence.DECLARED : Confidence.INFERRED,
                            List.of(declared ? InsightReason.TYPE_DECLARATION : InsightReason.JSON_VALUE));
                    var hint = RunningHubInputDiscovery.hint(declaredType, input.getKey(), nodeType, title);
                    if (hint.type() != declaredType || hint.promptPriority() != RunningHubInputDiscovery.PromptRole.NONE)
                        fieldEvidence.add(InsightReason.NAME_HINT);
                    var priority = hint.promptPriority();
                    if (negativeNodes.contains(node.getKey())) {
                        priority = RunningHubInputDiscovery.PromptRole.NONE;
                        String key = "input" + (fields.size() + 1);
                        if (positiveNodes.contains(node.getKey()) && hint.type() == RunningHubDefinition.FieldType.STRING) {
                            ambiguousFieldKeys.add(key);
                            fieldEvidence.uncertain(InsightReason.AMBIGUOUS_PROMPT);
                        } else if (hint.promptPriority() != RunningHubInputDiscovery.PromptRole.NONE) {
                            graphRecommendations.add(key);
                            fieldEvidence.add(InsightReason.NEGATIVE_PROMPT);
                        }
                    }
                    else if (priority != RunningHubInputDiscovery.PromptRole.NONE && positiveNodes.contains(node.getKey())) priority = RunningHubInputDiscovery.PromptRole.POSITIVE_CONDITIONING;
                    boolean creativeSource = creativeSources.contains(node.getKey() + ":" + input.getKey());
                    if (creativeSource) {
                        priority = RunningHubInputDiscovery.PromptRole.NONE;
                        fieldEvidence.add(InsightReason.DIRECT_CREATIVE_CONNECTION);
                    }
                    promptPriorities.add(priority);
                    if (creativeSource) graphRecommendations.add("input" + (fields.size() + 1));
                    var field = field(fields.size(), node.getKey(), input.getKey(), label, hint.type(), value, List.of(), null,
                            nodeType, RunningHubInputDiscovery.group(title));
                    addFieldEvidence(fieldEvidence, field, nodeType, title);
                    fields.add(field);
                    evidence.add(fieldEvidence);
                }
            }
            warnings.add(ApiMessage.of("api.running-hub-import-service.workflow-json-does-not-contain-complete-field-rules-internal-parameters"));
        } else {
            if (!source.isArray()) throw RunningHubDefinition.invalid(ApiMessage.of("api.running-hub-import-service.ai-application-requires-nodeinfolist-array"));
            for (JsonNode input : source) {
                String node = input.path("nodeId").asText();
                String name = input.path("fieldName").asText();
                var metadata = RunningHubInputMetadata.parse(mapper, input);
                var fieldEvidence = new Evidence(metadata.confidence(), metadata.reasons());
                if (metadata.missingOptions()) warnings.add(ApiMessage.of(
                        "api.running-hub-import-service.node-field-has-no-recognized-list-options-add-dropdown-options", node, name));
                String label = appLabel(input, name);
                // Conflicting widget declarations must not be silently overruled by a name-based media guess.
                var hint = metadata.ambiguousMedia()
                        ? new RunningHubInputDiscovery.Hint(metadata.type(), RunningHubInputDiscovery.PromptRole.NONE)
                        : RunningHubInputDiscovery.hint(metadata.type(), name, input.path("nodeName").asText(""), label);
                if (metadata.ambiguousMedia()) ambiguousFieldKeys.add("input" + (fields.size() + 1));
                var fieldType = hint.type();
                if (fieldType != metadata.type()) fieldEvidence.inferred();
                if (fieldType != metadata.type() || hint.promptPriority() != RunningHubInputDiscovery.PromptRole.NONE)
                    fieldEvidence.add(InsightReason.NAME_HINT);
                promptPriorities.add(hint.promptPriority());
                boolean media = Set.of(RunningHubDefinition.FieldType.IMAGE, RunningHubDefinition.FieldType.AUDIO,
                        RunningHubDefinition.FieldType.VIDEO).contains(fieldType);
                String nodeType = input.path("nodeName").asText("");
                var field = new RunningHubDefinition.Field("input" + (fields.size() + 1), label, input.path("description").asText(null),
                        fieldType, metadata.required(), media ? null : metadata.defaultValue(), metadata.minimum(), metadata.maximum(),
                        metadata.maxLength(), media ? List.of() : metadata.options(), false, node, name,
                        RunningHubDefinition.Source.PARAMETER, RunningHubDefinition.Encoding.NATIVE,
                        media ? RunningHubInputDiscovery.resourceFormat(name, nodeType) : null, null,
                        RunningHubInputDiscovery.group(label));
                addFieldEvidence(fieldEvidence, field, nodeType, label);
                fields.add(field);
                evidence.add(fieldEvidence);
            }
        }
        int primaryPrompt = RunningHubInputDiscovery.primaryPrompt(promptPriorities);
        if (primaryPrompt >= 0) {
            var prompt = fields.get(primaryPrompt);
            fields.set(primaryPrompt, new RunningHubDefinition.Field(prompt.key(), prompt.label(), prompt.description(), prompt.type(),
                    prompt.required(), prompt.defaultValue(), prompt.minimum(), prompt.maximum(), prompt.maxLength(), prompt.options(),
                    prompt.advanced(), prompt.nodeId(), prompt.fieldName(), RunningHubDefinition.Source.PROMPT,
                    prompt.encoding(), prompt.resourceFormat(), prompt.enabledWhen(), prompt.group()));
            evidence.get(primaryPrompt).add(InsightReason.PRIMARY_PROMPT);
        } else {
            var best = promptPriorities.stream().max(java.util.Comparator.naturalOrder()).orElse(RunningHubInputDiscovery.PromptRole.NONE);
            if (best != RunningHubInputDiscovery.PromptRole.NONE) for (int index = 0; index < fields.size(); index++)
                if (promptPriorities.get(index) == best) evidence.get(index).uncertain(InsightReason.AMBIGUOUS_PROMPT);
        }
        if (fields.size() > RunningHubDefinition.MAX_FIELDS) throw RunningHubDefinition.invalid(ApiMessage.of(
                "api.running-hub-import-service.field-limit-exceeded", RunningHubDefinition.MAX_FIELDS));
        RunningHubDefinition definition = new RunningHubDefinition(RunningHubDefinition.SCHEMA_VERSION, RunningHubDefinition.PROTOCOL_VERSION, type, targetId,
                List.copyOf(fields), List.of(), List.of(new RunningHubDefinition.Output(null, RunningHubDefinition.OutputKind.valueOf(kind.name().replace("_GENERATION", "")), true, 1)),
                "default", false, false, null, Sha256.hex(source.toString()),
                type == RunningHubDefinition.TargetType.WORKFLOW ? List.copyOf(nodeOptions) : null, source.deepCopy());
        definition.validate(kind);
        var recommended = fields.stream().filter(field -> !ambiguousFieldKeys.contains(field.key())
                        && (graphRecommendations.contains(field.key()) || RunningHubInputDiscovery.recommended(field)))
                .map(RunningHubDefinition.Field::key).toList();
        List<FieldInsight> insights = new ArrayList<>();
        for (int index = 0; index < fields.size(); index++) {
            var field = fields.get(index);
            var item = evidence.get(index);
            insights.add(new FieldInsight(field.key(), field.nodeId(), field.fieldName(), item.confidence, List.copyOf(item.reasons)));
        }
        return new Preview(definition, List.copyOf(warnings), recommended, null, List.copyOf(insights));
    }
    private RunningHubDefinition.Field field(int index, String node, String name, String label, RunningHubDefinition.FieldType type, JsonNode value,
            List<RunningHubDefinition.Option> options, String description, String nodeType, String group) {
        boolean media = Set.of(RunningHubDefinition.FieldType.IMAGE, RunningHubDefinition.FieldType.AUDIO, RunningHubDefinition.FieldType.VIDEO).contains(type);
        return new RunningHubDefinition.Field("input" + (index + 1), label, description, type, false, media ? null : value, null, null, null,
                options, false, node, name, RunningHubDefinition.Source.PARAMETER, RunningHubDefinition.Encoding.NATIVE,
                media ? RunningHubInputDiscovery.resourceFormat(name, nodeType) : null, null, group);
    }
    private void addFieldEvidence(Evidence evidence, RunningHubDefinition.Field field, String nodeType, String title) {
        if (field.type() == RunningHubDefinition.FieldType.STRING && RunningHubInputDiscovery.negativeInput(field.fieldName(), nodeType, title))
            evidence.add(InsightReason.NEGATIVE_PROMPT);
        if (field.group() != null) evidence.add(InsightReason.EXPLICIT_GROUP);
        if (field.media()) {
            if (RunningHubInputDiscovery.urlLoader(nodeType)) evidence.add(InsightReason.RESOURCE_URL_NODE);
            else if (RunningHubInputDiscovery.fileLoader(nodeType)) evidence.add(InsightReason.RESOURCE_FILE_NODE);
            else if (field.resourceFormat() != null) evidence.add(InsightReason.NAME_HINT);
            else evidence.uncertain(InsightReason.RESOURCE_FORMAT_UNCERTAIN);
        } else if (RunningHubInputDiscovery.recommended(field))
            evidence.add(InsightReason.NAME_HINT);
    }
    private static final class Evidence {
        private Confidence confidence;
        private final Set<InsightReason> reasons = new LinkedHashSet<>();
        private Evidence(Confidence confidence, List<InsightReason> reasons) {
            this.confidence = confidence;
            this.reasons.addAll(reasons);
        }
        private void add(InsightReason reason) { reasons.add(reason); }
        private void inferred() { if (confidence != Confidence.UNCERTAIN) confidence = Confidence.INFERRED; }
        private void uncertain(InsightReason reason) { confidence = Confidence.UNCERTAIN; add(reason); }
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
}
