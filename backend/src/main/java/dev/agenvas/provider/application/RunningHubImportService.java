package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.RunningHubClient;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
    public record Preview(RunningHubDefinition definition, List<String> warnings) {}

    public Preview preview(UUID connectionId, RunningHubDefinition.TargetType type, String targetId, Task.Kind kind, JsonNode source) {
        if (type == null || targetId == null || !targetId.matches("[0-9]{1,32}")
                || !Set.of(Task.Kind.IMAGE_GENERATION, Task.Kind.VIDEO_GENERATION, Task.Kind.AUDIO_GENERATION).contains(kind))
            throw RunningHubDefinition.invalid("需要真实目标 ID 和主输出类型；API 文档目录 ID 不能代替目标 ID");
        var connection = repository.connection(connectionId).orElseThrow(() -> RunningHubDefinition.invalid("连接不存在"));
        if (connection.platform() != MediaPlatform.RUNNINGHUB || !connection.enabled()) throw RunningHubDefinition.invalid("请选择启用的 RunningHub 连接");
        if (source == null || source.isNull()) {
            var version = repository.connectionVersion(connectionId, connection.currentVersion()).orElseThrow();
            String key = cipher.decryptMedia(connectionId, version.version(), new CredentialCipher.Encrypted(version.credentialCiphertext(), version.credentialNonce(), version.credentialKeyVersion()));
            try { source = client.metadata(version.origin(), key, type, targetId); }
            catch (RuntimeException unavailable) {
                throw new ApiProblemException(HttpStatus.BAD_GATEWAY, "RUNNINGHUB_DISCOVERY_UNAVAILABLE", "参数发现不可用",
                        "请核对目标 ID 与权限，或导入脱敏的 nodeInfoList / ComfyUI API-format JSON。应用发现不会把 Key 放入 URL。", true);
            }
            // Only the input contract is retained. Upstream demos may also include curl and credentials.
            if (type == RunningHubDefinition.TargetType.AI_APP) source = source.path("nodeInfoList");
        }
        return candidates(type, targetId, kind, source);
    }

    Preview candidates(RunningHubDefinition.TargetType type, String targetId, Task.Kind kind, JsonNode source) {
        if (source == null || source.toString().getBytes(StandardCharsets.UTF_8).length > RunningHubDefinition.MAX_DEFINITION_BYTES)
            throw RunningHubDefinition.invalid("导入 JSON 超过大小上限");
        rejectCredentials(source);
        if (source.path("data").has("prompt")) source = source.path("data").path("prompt");
        if (source.isTextual()) {
            try { source = mapper.readTree(source.asText()); }
            catch (RuntimeException invalid) { throw RunningHubDefinition.invalid("API-format JSON 字符串无效"); }
        }
        if (source.has("data")) source = source.path("data");
        if (source.has("nodeInfoList")) source = source.path("nodeInfoList");
        rejectCredentials(source);
        List<RunningHubDefinition.Field> fields = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        warnings.add("导入结果是候选字段。请确认开放字段、中文名称、必填规则、范围、素材上传格式和输出节点后发布。");
        if (type == RunningHubDefinition.TargetType.WORKFLOW) {
            if (!source.isObject()) throw RunningHubDefinition.invalid("工作流需要 ComfyUI API-format 对象");
            for (var node : source.properties()) {
                if (!node.getKey().matches("[0-9]{1,32}")) throw RunningHubDefinition.invalid("工作流节点 ID 无效");
                for (var input : node.getValue().path("inputs").properties()) {
                    JsonNode value = input.getValue();
                    // Connections, arrays and nested node configurations are never exposed as editable fields.
                    if (!(value.isTextual() || value.isNumber() || value.isBoolean())) continue;
                    if (!RunningHubDefinition.bindableFieldName(input.getKey())) {
                        warnings.add("节点 " + node.getKey() + " 的 " + input.getKey() + " 不符合支持的映射格式，已跳过；请核对实际运行参数。");
                        continue;
                    }
                    String label = node.getValue().path("_meta").path("title").asText(node.getValue().path("class_type").asText("节点 " + node.getKey())) + " · " + input.getKey();
                    fields.add(field(fields.size(), node.getKey(), input.getKey(), label,
                            value.isBoolean() ? RunningHubDefinition.FieldType.BOOLEAN : value.isIntegralNumber() ? RunningHubDefinition.FieldType.INTEGER
                                    : value.isNumber() ? RunningHubDefinition.FieldType.NUMBER : RunningHubDefinition.FieldType.STRING, value, List.of(), null));
                }
            }
            warnings.add("工作流 JSON 不包含完整字段规则；模型名、路径与种子等内部参数不会自动识别用途，需人工选择。");
        } else {
            if (!source.isArray()) throw RunningHubDefinition.invalid("AI 应用需要 nodeInfoList 数组");
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
                    warnings.add("节点 " + node + " 的 " + name + " 缺少可识别的 LIST 选项，请补充下拉选项。");
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
                fields.add(field(fields.size(), node, name, input.path("nodeName").asText(name), fieldType, value, options, input.path("description").asText(null)));
            }
        }
        if (fields.size() > RunningHubDefinition.MAX_FIELDS) throw RunningHubDefinition.invalid("字段超过 64 项，请导入选定字段");
        RunningHubDefinition definition = new RunningHubDefinition(RunningHubDefinition.SCHEMA_VERSION, RunningHubDefinition.PROTOCOL_VERSION, type, targetId,
                List.copyOf(fields), List.of(), List.of(new RunningHubDefinition.Output(null, RunningHubDefinition.OutputKind.valueOf(kind.name().replace("_GENERATION", "")), true, 1)),
                "default", false, false, null, sha256(source.toString()));
        definition.validate(kind);
        return new Preview(definition, List.copyOf(warnings));
    }
    private RunningHubDefinition.Field field(int index, String node, String name, String label, RunningHubDefinition.FieldType type, JsonNode value, List<RunningHubDefinition.Option> options, String description) {
        return new RunningHubDefinition.Field("input" + (index + 1), label, description, type, false, value, null, null, null,
                options, false, node, name, RunningHubDefinition.Source.PARAMETER, RunningHubDefinition.Encoding.NATIVE,
                RunningHubDefinition.ResourceFormat.FILE_NAME, null);
    }
    private List<RunningHubDefinition.Option> options(JsonNode data) {
        if (data == null || data.isNull()) return List.of();
        if (data.isTextual()) {
            try { data = mapper.readTree(data.asText()); } catch (RuntimeException invalid) { return List.of(); }
        }
        if (data.isObject()) data = data.path("options");
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
            if (SENSITIVE.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT))) throw RunningHubDefinition.invalid("请先移除凭据字段再导入；不要粘贴带 Key 的请求示例");
            rejectCredentials(entry.getValue());
        }
        if (node.isArray()) for (JsonNode child : node) rejectCredentials(child);
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
