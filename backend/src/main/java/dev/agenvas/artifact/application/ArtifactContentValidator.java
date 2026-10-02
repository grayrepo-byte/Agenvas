package dev.agenvas.artifact.application;

import dev.agenvas.shared.i18n.ApiMessage;
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
    /** Empty text is a persisted starting version for direct canvas creation. */
    private static final int MIN_TEXT_LENGTH = 0;
    private static final int MAX_TEXT_LENGTH = 20_000;
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
            throw invalid(ApiMessage.of("api.artifact-content-validator.content-cannot-exceed-256-kib"));
        }
        rejectProtectedFields(content);
        return switch (kind) {
            case TEXT -> validateText(content);
            case IMAGE, VIDEO, AUDIO -> validateMedia(content);
        };
    }

    /** 文本只允许 PLAIN_TEXT/MARKDOWN 格式和正文，不存在语义版本引用。 */
    private List<ArtifactVersion.InputReference> validateText(JsonNode content) {
        allowOnly(content, "format", "text");
        requireEnum(content, "format", "PLAIN_TEXT", "MARKDOWN");
        requireText(content, "text", MIN_TEXT_LENGTH, MAX_TEXT_LENGTH);
        return List.of();
    }

    /** 区分用户图片上传和任务生成媒体；生成结果必须保留提示、固定工作流与来源任务。 */
    private List<ArtifactVersion.InputReference> validateMedia(JsonNode content) {
        if (java.util.Set.of(ArtifactVersion.MediaSourceType.UPLOAD.name(),
                ArtifactVersion.MediaSourceType.LIBRARY_IMPORT.name(),
                ArtifactVersion.MediaSourceType.SKILL_IMPORT.name()).contains(content.path("sourceType").asText())) {
            allowOnly(content, "sourceType", "assetId");
            requireUuid(content, "assetId");
            return List.of();
        }
        allowOnly(content, "assetId", "prompt", "negativePrompt",
                "workflowVersion", "parameters", "sourceTaskId");
        requireUuid(content, "assetId");
        // Some published workflow/app contracts have no prompt input. Task acceptance
        // checks required inputs; immutable results retain the actual, possibly empty text.
        requireText(content, "prompt", 0, 20_000);
        optionalText(content, "negativePrompt", 8_000);
        requireText(content, "workflowVersion", 1, 120);
        requireObject(content.get("parameters"), "parameters");
        requireUuid(content, "sourceTaskId");
        return List.of();
    }

    /** 对当前对象执行白名单校验，防止应用忽略的字段被误当作已接受内容。 */
    private void allowOnly(JsonNode content, String... allowedNames) {
        Set<String> allowed = Set.of(allowedNames);
        for (String propertyName : content.propertyNames()) {
            if (!allowed.contains(propertyName)) {
                throw invalid(ApiMessage.of("api.artifact-content-validator.content-contains-a-disallowed-field", propertyName));
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
                throw invalid(ApiMessage.of("api.artifact-content-validator.the-content-structure-is-too-complex"));
            }
            if (node.isObject()) {
                for (String propertyName : node.propertyNames()) {
                    String normalized = propertyName
                            .replace("_", "")
                            .replace("-", "")
                            .toLowerCase(java.util.Locale.ROOT);
                    if (PROTECTED_FIELD_NAMES.contains(normalized)) {
                        throw invalid(ApiMessage.of("api.artifact-content-validator.content-cannot-contain-the-protected-field", propertyName));
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
            throw invalid(ApiMessage.of("api.artifact-content-validator.must-be-a-json-object", field));
        }
    }

    /** 校验字符串并去除首尾空白后检查字符长度，返回规范化文本供进一步判定。 */
    private String requireText(JsonNode content, String field, int minimum, int maximum) {
        JsonNode value = content.get(field);
        if (value == null || !value.isString()) {
            throw invalid(ApiMessage.of("api.artifact-content-validator.must-be-a-string", field));
        }
        String text = value.stringValue().trim();
        if (text.length() < minimum || text.length() > maximum) {
            throw invalid(ApiMessage.of("api.artifact-content-validator.the-length-of-must-be-between-and", field, minimum, maximum));
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
            throw invalid(ApiMessage.of("api.artifact-content-validator.must-be-a-string-no-longer-than-characters", field, maximum));
        }
    }

    /** 先按普通文本校验字段，再与明确白名单作区分大小写匹配。 */
    private void requireEnum(JsonNode content, String field, String... values) {
        String actual = requireText(content, field, 1, 80);
        if (!Set.of(values).contains(actual)) {
            throw invalid(ApiMessage.of("api.artifact-content-validator.is-not-an-allowed-enum-value", field));
        }
    }

    /** 从正文必填字段解析 UUID。 */
    private UUID requireUuid(JsonNode content, String field) {
        return parseUuid(content.get(field), field);
    }

    /** 解析 UUID 字符串并将结构错误转换为产物 Schema 错误。 */
    private UUID parseUuid(JsonNode value, String field) {
        if (value == null || !value.isString()) {
            throw invalid(ApiMessage.of("api.artifact-content-validator.must-be-a-uuid-string", field));
        }
        try {
            return UUID.fromString(value.stringValue());
        } catch (IllegalArgumentException invalidUuid) {
            throw invalid(ApiMessage.of("api.artifact-content-validator.must-be-a-uuid-string", field));
        }
    }

    /** 将正文结构或字段约束失败映射为稳定的 HTTP 400 错误。 */
    private ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "ARTIFACT_SCHEMA_INVALID",
                ApiMessage.of("api.artifact-content-validator.product-content-is-invalid"),
                detail,
                false);
    }
}
