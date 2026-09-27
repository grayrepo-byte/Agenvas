package dev.agenvas.artifact.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 严格校验三类版本化产物正文，拒绝未知/越权字段并提取类型化精确版本引用。 */
@Component
public class ArtifactContentValidator {

    /** JSON 序列化后的正文最大 UTF-8 字节数。 */
    private static final int MAX_CONTENT_BYTES = 262_144;
    /** 递归字段保护扫描允许访问的 JSON 节点数。 */
    private static final int MAX_CONTENT_NODES = 10_000;
    /** 无论嵌套层级如何都不允许出现在用户或模型正文中的身份、权限和凭证字段。 */
    private static final Set<String> PROTECTED_FIELD_NAMES = Set.of(
            "ownerid",
            "userid",
            "projectid",
            "storagekey",
            "approvalstatus",
            "approved",
            "credential",
            "apikey",
            "endpoint");

    /**
     * 校验完整正文，不接受部分更新；按类型分派 Schema 并提取版本引用。
     * 字节大小和所有嵌套字段先经过统一限制，避免正文携带身份、审批或端点数据。
     *
     * @param kind 正文所属产物类型
     * @param content 用户、Agent 或任务提交的完整 JSON 正文
     * @return 按角色和顺序记录的精确输入版本引用
     */
    public List<ArtifactVersion.InputReference> validate(
            Artifact.Kind kind, JsonNode content) {
        requireObject(content, "content");
        if (content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_CONTENT_BYTES) {
            throw invalid("content 不能超过 256 KiB。");
        }
        rejectProtectedFields(content);
        return switch (kind) {
            case TEXT -> validateText(content);
            case IMAGE -> validateMedia(content, false);
            case VIDEO -> validateMedia(content, true);
        };
    }

    /** 文本只允许 PLAIN_TEXT/MARKDOWN 格式和正文，不存在语义版本引用。 */
    private List<ArtifactVersion.InputReference> validateText(JsonNode content) {
        allowOnly(content, "format", "text");
        requireEnum(content, "format", "PLAIN_TEXT", "MARKDOWN");
        requireText(content, "text", 1, 20_000);
        return List.of();
    }

    /** 区分用户图片上传和任务生成媒体；生成结果必须保留提示、配置、工作流与来源任务。 */
    private List<ArtifactVersion.InputReference> validateMedia(JsonNode content, boolean video) {
        if (!video && "UPLOAD".equals(content.path("sourceType").asText())) {
            allowOnly(content, "sourceType", "assetId");
            requireUuid(content, "assetId");
            return List.of();
        }
        if (video) {
            allowOnly(content, "assetId", "prompt", "negativePrompt",
                    "providerConfigVersion", "workflowVersion", "parameters",
                    "sourceTaskId", "keyframeVersionId");
        } else {
            allowOnly(content, "assetId", "prompt", "negativePrompt",
                    "providerConfigVersion", "workflowVersion", "parameters", "sourceTaskId");
        }
        requireUuid(content, "assetId");
        requireText(content, "prompt", 1, 8_000);
        optionalText(content, "negativePrompt", 8_000);
        requireInteger(content, "providerConfigVersion", 1, Integer.MAX_VALUE);
        requireText(content, "workflowVersion", 1, 120);
        requireObject(content.get("parameters"), "parameters");
        requireUuid(content, "sourceTaskId");
        return video ? optionalReference(content, "keyframeVersionId", "keyframe",
                Artifact.Kind.IMAGE).stream().toList() : List.of();
    }

    /** 仅缺失或 JSON null 可省略；其他类型必须是可解析 UUID。 */
    private java.util.Optional<ArtifactVersion.InputReference> optionalReference(
            JsonNode content, String field, String role, Artifact.Kind expectedKind) {
        JsonNode value = content.get(field);
        if (value == null || value.isNull()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ArtifactVersion.InputReference(
                parseUuid(value, field), role, 0, expectedKind));
    }

    /** 对当前对象执行白名单校验，防止应用忽略的字段被误当作已接受内容。 */
    private void allowOnly(JsonNode content, String... allowedNames) {
        Set<String> allowed = Set.of(allowedNames);
        for (String propertyName : content.propertyNames()) {
            if (!allowed.contains(propertyName)) {
                throw invalid("content 包含不允许的字段：" + propertyName + "。");
            }
        }
    }

    /** 广度优先扫描嵌套对象，规范化大小写、下划线和连字符后拒绝受保护字段。 */
    private void rejectProtectedFields(JsonNode content) {
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(content);
        int visited = 0;
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeFirst();
            if (++visited > MAX_CONTENT_NODES) {
                throw invalid("content 结构过于复杂。");
            }
            if (node.isObject()) {
                for (String propertyName : node.propertyNames()) {
                    String normalized = propertyName
                            .replace("_", "")
                            .replace("-", "")
                            .toLowerCase(java.util.Locale.ROOT);
                    if (PROTECTED_FIELD_NAMES.contains(normalized)) {
                        throw invalid("content 不能包含受保护字段：" + propertyName + "。");
                    }
                    pending.addLast(node.get(propertyName));
                }
            } else if (node.isArray()) {
                node.forEach(pending::addLast);
            }
        }
    }

    /** 要求字段存在且为 JSON 对象。 */
    private void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw invalid(field + " 必须是 JSON 对象。");
        }
    }

    /** 校验字符串并去除首尾空白后检查字符长度，返回规范化文本供进一步判定。 */
    private String requireText(JsonNode content, String field, int minimum, int maximum) {
        JsonNode value = content.get(field);
        if (value == null || !value.isString()) {
            throw invalid(field + " 必须是字符串。");
        }
        String text = value.stringValue().trim();
        if (text.length() < minimum || text.length() > maximum) {
            throw invalid(field + " 长度必须在 " + minimum + " 到 " + maximum + " 之间。");
        }
        return text;
    }

    /** 可选文本允许缺失或 null；若提供则必须为不超过最大长度的字符串。 */
    private void optionalText(JsonNode content, String field, int maximum) {
        JsonNode value = content.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isString() || value.stringValue().length() > maximum) {
            throw invalid(field + " 必须是长度不超过 " + maximum + " 的字符串。");
        }
    }

    /** 先按普通文本校验字段，再与明确白名单作区分大小写匹配。 */
    private void requireEnum(JsonNode content, String field, String... values) {
        String actual = requireText(content, field, 1, 80);
        if (!Set.of(values).contains(actual)) {
            throw invalid(field + " 不在允许的枚举值中。");
        }
    }

    /** 要求 JSON 整数并检查闭区间；小数或超界值均拒绝。 */
    private int requireInteger(JsonNode content, String field, int minimum, int maximum) {
        JsonNode value = content.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid(field + " 必须是整数。");
        }
        int number = value.intValue();
        if (number < minimum || number > maximum) {
            throw invalid(field + " 必须在 " + minimum + " 到 " + maximum + " 之间。");
        }
        return number;
    }

    /** 从正文必填字段解析 UUID。 */
    private UUID requireUuid(JsonNode content, String field) {
        return parseUuid(content.get(field), field);
    }

    /** 解析 UUID 字符串并将结构错误转换为产物 Schema 错误。 */
    private UUID parseUuid(JsonNode value, String field) {
        if (value == null || !value.isString()) {
            throw invalid(field + " 必须是 UUID 字符串。");
        }
        try {
            return UUID.fromString(value.stringValue());
        } catch (IllegalArgumentException invalidUuid) {
            throw invalid(field + " 必须是 UUID 字符串。");
        }
    }

    /** 将正文结构或字段约束失败映射为稳定的 HTTP 400 错误。 */
    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "ARTIFACT_SCHEMA_INVALID",
                "产物内容无效",
                detail,
                false);
    }
}
