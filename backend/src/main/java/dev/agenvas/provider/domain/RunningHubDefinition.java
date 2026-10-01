package dev.agenvas.provider.domain;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A data-only input contract; provider URLs, scripts and credentials are never template fields. */
public record RunningHubDefinition(int schemaVersion, String protocolVersion, TargetType targetType,
        String targetId, List<Field> fields, List<FixedBinding> fixedBindings, List<Output> outputs,
        String instanceType, boolean usePersonalQueue, boolean addMetadata, Integer retainSeconds,
        String sourceSha256) {
    public static final int SCHEMA_VERSION = 1;
    public static final String PROTOCOL_VERSION = "V2";
    public static final int MAX_FIELDS = 64;
    public static final int MAX_OUTPUTS = 16;
    public static final int MAX_TEXT_LENGTH = 20_000;
    public static final int MAX_OPTIONS = 100;
    public static final int MAX_DEFINITION_BYTES = 256 * 1024;
    public static final String VALUES_PROPERTY = "dynamicValues";
    private static final int MAX_LABEL_LENGTH = 160;
    private static final int MAX_DESCRIPTION_LENGTH = 1_000;
    private static final int MIN_RETAIN_SECONDS = 10;
    private static final int MAX_RETAIN_SECONDS = 180;
    private static final Set<String> INSTANCES = Set.of("default", "plus", "ultra");
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "protocolVersion",
            "targetType", "targetId", "fields", "fixedBindings", "outputs", "instanceType",
            "usePersonalQueue", "addMetadata", "retainSeconds", "sourceSha256");
    private static final Set<String> FIELD_FIELDS = Set.of("key", "label", "description", "type",
            "required", "defaultValue", "minimum", "maximum", "maxLength", "options",
            "advanced", "nodeId", "fieldName", "source", "encoding", "resourceFormat", "enabledWhen");
    private static final Set<String> RESERVED_KEYS = Set.of("apiKey", "accessPassword", "password",
            "authorization", "endpoint", "webhookUrl", "__proto__", "constructor", "prototype");
    public enum TargetType { WORKFLOW, AI_APP }
    public enum FieldType { STRING, NUMBER, INTEGER, BOOLEAN, SELECT, IMAGE, AUDIO, VIDEO }
    public enum Source { PARAMETER, PROMPT, DURATION_SECONDS }
    public enum Encoding { NATIVE, STRING }
    public enum ResourceFormat { FILE_NAME, URL }
    public enum OutputKind { IMAGE, VIDEO, AUDIO }
    public record Option(String label, JsonNode value) {}
    public record Condition(String field, JsonNode value) {}
    public record Field(String key, String label, String description, FieldType type,
            boolean required, JsonNode defaultValue, BigDecimal minimum, BigDecimal maximum,
            Integer maxLength, List<Option> options, boolean advanced, String nodeId,
            String fieldName, Source source, Encoding encoding, ResourceFormat resourceFormat,
            Condition enabledWhen) {
        public boolean media() { return type == FieldType.IMAGE || type == FieldType.AUDIO || type == FieldType.VIDEO; }
        public Source effectiveSource() { return source == null ? Source.PARAMETER : source; }
        public Encoding effectiveEncoding() { return encoding == null ? Encoding.NATIVE : encoding; }
        public ResourceFormat effectiveResourceFormat() { return resourceFormat == null ? ResourceFormat.FILE_NAME : resourceFormat; }
    }
    public record FixedBinding(String nodeId, String fieldName, JsonNode value, Encoding encoding) {}
    public record Output(String nodeId, OutputKind kind, boolean primary, int maxCount) {}

    public static RunningHubDefinition parse(ObjectMapper mapper, JsonNode value, Task.Kind kind) {
        requireObject(value, ROOT_FIELDS);
        if (value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DEFINITION_BYTES)
            throw invalid("能力定义超过大小上限");
        for (JsonNode field : value.path("fields")) {
            requireObject(field, FIELD_FIELDS);
            for (JsonNode option : field.path("options")) requireObject(option, Set.of("label", "value"));
            if (field.hasNonNull("enabledWhen")) requireObject(field.get("enabledWhen"), Set.of("field", "value"));
        }
        for (JsonNode binding : value.path("fixedBindings")) requireObject(binding, Set.of("nodeId", "fieldName", "value", "encoding"));
        for (JsonNode output : value.path("outputs")) requireObject(output, Set.of("nodeId", "kind", "primary", "maxCount"));
        RunningHubDefinition definition;
        ObjectNode normalized = (ObjectNode) value.deepCopy();
        for (String flag : List.of("usePersonalQueue", "addMetadata")) if (!normalized.has(flag)) normalized.put(flag, false);
        for (JsonNode field : normalized.path("fields")) {
            for (String flag : List.of("required", "advanced")) if (!field.has(flag)) ((ObjectNode) field).put(flag, false);
        }
        try { definition = mapper.treeToValue(normalized, RunningHubDefinition.class); }
        catch (RuntimeException failure) { throw invalid("RunningHub 能力字段类型无效"); }
        definition.validate(kind);
        return definition;
    }

    public void validate(Task.Kind kind) {
        if (schemaVersion != SCHEMA_VERSION || !PROTOCOL_VERSION.equals(protocolVersion)
                || targetType == null || targetId == null || !targetId.matches("[0-9]{1,32}"))
            throw invalid("必须指定 V2 协议、目标类型与真实目标 ID");
        if (fields == null || fields.size() > MAX_FIELDS || outputs == null || outputs.isEmpty()
                || outputs.size() > MAX_OUTPUTS || fixedBindings != null && fixedBindings.size() > MAX_FIELDS)
            throw invalid("能力字段或输出数量无效");
        if (instanceType != null && !INSTANCES.contains(instanceType)
                || retainSeconds != null && (retainSeconds < MIN_RETAIN_SECONDS || retainSeconds > MAX_RETAIN_SECONDS)
                || sourceSha256 != null && !sourceSha256.matches("[0-9a-f]{64}"))
            throw invalid("实例选项或来源摘要无效");
        Set<String> keys = new HashSet<>();
        Set<String> bindings = new HashSet<>();
        Set<Source> sources = new HashSet<>();
        for (Field field : fields) {
            if (field == null || field.type() == null || field.key() == null
                    || !field.key().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || RESERVED_KEYS.contains(field.key()) || !keys.add(field.key()))
                throw invalid("参数键必须有效、唯一且不能包含凭据字段");
            label(field.label());
            if (field.description() != null && field.description().length() > MAX_DESCRIPTION_LENGTH)
                throw invalid("参数说明过长");
            binding(field.nodeId(), field.fieldName(), bindings);
            if (field.effectiveSource() != Source.PARAMETER && !sources.add(field.effectiveSource()))
                throw invalid("提示词与时长来源不能重复绑定");
            if (field.effectiveSource() == Source.PROMPT && field.type() != FieldType.STRING
                    || field.effectiveSource() == Source.DURATION_SECONDS && field.type() != FieldType.INTEGER
                    || field.media() && field.effectiveSource() != Source.PARAMETER)
                throw invalid("参数来源与类型不匹配");
            if (field.minimum() != null && field.maximum() != null && field.minimum().compareTo(field.maximum()) > 0
                    || field.maxLength() != null && (field.maxLength() < 1 || field.maxLength() > MAX_TEXT_LENGTH))
                throw invalid("参数范围无效");
            if (field.type() == FieldType.SELECT && (field.options() == null || field.options().isEmpty())
                    || field.options() != null && field.options().size() > MAX_OPTIONS)
                throw invalid("下拉选项数量无效");
            Set<String> options = new HashSet<>();
            if (field.options() != null) for (Option option : field.options()) {
                if (option == null) throw invalid("下拉选项无效");
                label(option.label());
                scalar(option.value());
                String canonical = option.value().isNumber() ? "number:" + option.value().decimalValue().stripTrailingZeros()
                        : option.value().toString();
                if (!options.add(canonical)) throw invalid("下拉选项值重复");
            }
            if (field.defaultValue() != null && !field.defaultValue().isNull()) {
                if (field.media()) throw invalid("素材默认值不能包含项目资源或外部 URL");
                validateValue(field, field.defaultValue());
            }
        }
        for (Field field : fields) if (field.enabledWhen() != null) {
            Condition condition = field.enabledWhen();
            Field parent = fields.stream().filter(candidate -> candidate.key().equals(condition.field())).findFirst()
                    .orElseThrow(() -> invalid("条件字段不存在"));
            if (parent == field || parent.enabledWhen() != null || parent.media())
                throw invalid("显示条件只能引用无条件的普通参数");
            validateValue(parent, condition.value());
        }
        if (fixedBindings != null) for (FixedBinding fixed : fixedBindings) {
            if (fixed == null) throw invalid("固定映射无效");
            binding(fixed.nodeId(), fixed.fieldName(), bindings);
            scalar(fixed.value());
        }
        if (outputs.stream().anyMatch(java.util.Objects::isNull)) throw invalid("输出映射无效");
        long primaryCount = outputs.stream().filter(Output::primary).count();
        if (primaryCount != 1) throw invalid("必须指定一个主输出");
        Set<String> outputKeys = new HashSet<>();
        int totalOutputs = 0;
        for (Output output : outputs) {
            if (output == null || output.kind() == null || output.maxCount() < 1 || output.maxCount() > MAX_OUTPUTS
                    || output.nodeId() != null && !output.nodeId().matches("[0-9]{1,32}")
                    || !outputKeys.add(output.nodeId() + ":" + output.kind())) throw invalid("输出映射无效或重复");
            if (output.primary() && !output.kind().name().equals(kind.name().replace("_GENERATION", "")))
                throw invalid("主输出与能力媒体类型不匹配");
            totalOutputs += output.maxCount();
            if (outputs.stream().anyMatch(other -> other != output && other.kind() == output.kind()
                    && (other.nodeId() == null || output.nodeId() == null)))
                throw invalid("同一媒体类型的通配输出不能与其他节点输出重叠");
        }
        if (totalOutputs > MAX_OUTPUTS) throw invalid("所有输出映射的结果上限合计不能超过 16");
    }

    /** Incomplete drafts are legal; execution resolves defaults and requires all active inputs. */
    public ObjectNode values(ObjectMapper mapper, JsonNode parameters, String prompt, Integer seconds,
            boolean executing) {
        JsonNode supplied = parameters == null ? mapper.createObjectNode() : parameters;
        requireObject(supplied, Set.of(VALUES_PROPERTY));
        JsonNode raw = supplied.path(VALUES_PROPERTY);
        if (!raw.isMissingNode() && !raw.isObject()) throw invalid("动态参数必须是对象");
        for (String key : raw.propertyNames()) if (fields.stream().noneMatch(field -> field.key().equals(key)))
            throw invalid("存在当前能力不支持的参数：" + key);
        ObjectNode effective = mapper.createObjectNode();
        for (Field field : fields) {
            JsonNode value = switch (field.effectiveSource()) {
                case PROMPT -> mapper.valueToTree(prompt == null ? "" : prompt);
                case DURATION_SECONDS -> mapper.valueToTree(seconds);
                case PARAMETER -> raw.get(field.key());
            };
            if (executing && (value == null || value.isNull() || field.effectiveSource() == Source.PROMPT && value.isTextual() && value.asText().isBlank())) value = field.defaultValue();
            if (value != null && !value.isNull()) {
                validateValue(field, value);
                effective.set(field.key(), value.deepCopy());
            }
        }
        for (Field field : fields) {
            boolean active = field.enabledWhen() == null || scalarEquals(
                    effective.get(field.enabledWhen().field()), field.enabledWhen().value());
            if (!active) { effective.remove(field.key()); continue; }
            JsonNode value = effective.get(field.key());
            if (executing && field.required() && (value == null || value.isNull()
                    || value.isTextual() && value.asText().isBlank())) throw invalid("请填写“" + field.label() + "”");
        }
        return effective;
    }

    public boolean hasSource(Source source) { return fields.stream().anyMatch(field -> field.effectiveSource() == source); }

    /** JSON numbers have value semantics: browsers serialize 1.0 as 1, without changing an enum or condition. */
    public static boolean scalarEquals(JsonNode left, JsonNode right) {
        return left != null && right != null && left.isNumber() && right.isNumber()
                ? left.decimalValue().compareTo(right.decimalValue()) == 0 : java.util.Objects.equals(left, right);
    }

    private static void validateValue(Field field, JsonNode value) {
        if (value == null || value.isNull()) return;
        scalar(value);
        boolean valid = switch (field.type()) {
            case STRING -> value.isTextual() && value.asText().length() <= (field.maxLength() == null ? MAX_TEXT_LENGTH : field.maxLength());
            case INTEGER -> value.isIntegralNumber() && value.canConvertToLong();
            case NUMBER -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case SELECT -> field.options().stream().anyMatch(option -> scalarEquals(option.value(), value));
            case IMAGE, AUDIO, VIDEO -> value.isTextual() && uuid(value.asText());
        };
        if (valid && field.effectiveSource() == Source.DURATION_SECONDS) valid = value.asLong() >= 1 && value.asLong() <= MediaAdapterRegistry.RUNNINGHUB_MAX_VIDEO_SECONDS;
        if (valid && value.isNumber()) valid = (field.minimum() == null || value.decimalValue().compareTo(field.minimum()) >= 0)
                && (field.maximum() == null || value.decimalValue().compareTo(field.maximum()) <= 0);
        if (!valid) throw invalid("“" + field.label() + "”的类型或范围无效");
    }

    private static boolean uuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static void scalar(JsonNode value) {
        if (value == null || value.isNull() || !(value.isTextual() || value.isNumber() || value.isBoolean())
                || value.isTextual() && value.asText().length() > MAX_TEXT_LENGTH) throw invalid("参数值必须是有界的文字、数字或布尔值");
    }
    private static void label(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LABEL_LENGTH) throw invalid("字段名称必须为 1–160 字符");
    }
    private static void binding(String node, String field, Set<String> seen) {
        if (node == null || !node.matches("[0-9]{1,32}") || !bindableFieldName(field)
                || !seen.add(node + ":" + field)) throw invalid("节点映射必须有效且不能重复");
    }
    /** Discovery also encounters ComfyUI upload-widget labels that are not supported bindings. */
    public static boolean bindableFieldName(String field) {
        return field != null && field.matches("[A-Za-z_][A-Za-z0-9_]{0,79}") && !RESERVED_KEYS.contains(field);
    }
    private static void requireObject(JsonNode value, Set<String> allowed) {
        if (value == null || !value.isObject() || !allowed.containsAll(value.propertyNames())) throw invalid("能力定义包含未知字段或无效对象");
    }
    public static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "RUNNINGHUB_INPUT_INVALID",
                "RunningHub 配置或输入无效", detail, false);
    }
}
