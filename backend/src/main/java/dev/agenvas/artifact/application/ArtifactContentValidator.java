package dev.agenvas.artifact.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Strictly validates the six versioned Artifact content schemas and extracts typed references. */
@Component
public class ArtifactContentValidator {

    private static final int MAX_CONTENT_BYTES = 262_144;
    private static final int MAX_CONTENT_NODES = 10_000;
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

    /** Validates one complete content object; partial or unknown fields are rejected. */
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
            case CHARACTER -> validateCharacter(content);
            case SCENE -> validateScene(content);
            case SHOT -> validateShot(content);
            case IMAGE -> validateMedia(content, false);
            case VIDEO -> validateMedia(content, true);
        };
    }

    private List<ArtifactVersion.InputReference> validateText(JsonNode content) {
        allowOnly(content, "format", "text");
        requireEnum(content, "format", "PLAIN_TEXT", "MARKDOWN");
        requireText(content, "text", 1, 20_000);
        return List.of();
    }

    private List<ArtifactVersion.InputReference> validateCharacter(JsonNode content) {
        allowOnly(
                content, "name", "description", "appearance", "referenceVersionIds");
        requireText(content, "name", 1, 120);
        requireText(content, "description", 1, 4_000);
        requireText(content, "appearance", 1, 4_000);
        return arrayReferences(
                content, "referenceVersionIds", "referenceImage", Artifact.Kind.IMAGE, 8);
    }

    private List<ArtifactVersion.InputReference> validateScene(JsonNode content) {
        allowOnly(
                content,
                "name",
                "location",
                "timeOfDay",
                "lighting",
                "style",
                "referenceVersionIds");
        requireText(content, "name", 1, 120);
        requireText(content, "location", 1, 500);
        requireText(content, "timeOfDay", 1, 80);
        requireText(content, "lighting", 1, 1_000);
        requireText(content, "style", 1, 1_000);
        return arrayReferences(
                content, "referenceVersionIds", "referenceImage", Artifact.Kind.IMAGE, 8);
    }

    private List<ArtifactVersion.InputReference> validateShot(JsonNode content) {
        allowOnly(
                content,
                "order",
                "durationMs",
                "description",
                "camera",
                "action",
                "characterVersionIds",
                "sceneVersionId",
                "selectedImageVersionId",
                "selectedVideoVersionId");
        requireInteger(content, "order", 1, 6);
        requireInteger(content, "durationMs", 100, 30_000);
        requireText(content, "description", 1, 4_000);
        requireText(content, "camera", 1, 1_000);
        requireText(content, "action", 1, 2_000);
        List<ArtifactVersion.InputReference> references = new ArrayList<>(arrayReferences(
                content,
                "characterVersionIds",
                "character",
                Artifact.Kind.CHARACTER,
                10));
        references.add(requiredReference(
                content, "sceneVersionId", "scene", 0, Artifact.Kind.SCENE));
        optionalReference(content, "selectedImageVersionId", "selectedImage", Artifact.Kind.IMAGE)
                .ifPresent(references::add);
        optionalReference(content, "selectedVideoVersionId", "selectedVideo", Artifact.Kind.VIDEO)
                .ifPresent(references::add);
        return List.copyOf(references);
    }

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

    private List<ArtifactVersion.InputReference> arrayReferences(
            JsonNode content,
            String field,
            String role,
            Artifact.Kind expectedKind,
            int maximumItems) {
        JsonNode values = content.get(field);
        if (values == null || !values.isArray() || values.size() > maximumItems) {
            throw invalid(field + " 必须是最多 " + maximumItems + " 项的数组。");
        }
        List<ArtifactVersion.InputReference> references = new ArrayList<>();
        Set<UUID> uniqueIds = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            UUID versionId = parseUuid(values.get(index), field + "[" + index + "]");
            if (!uniqueIds.add(versionId)) {
                throw invalid(field + " 不能包含重复版本。");
            }
            references.add(new ArtifactVersion.InputReference(
                    versionId, role, index, expectedKind));
        }
        return references;
    }

    private ArtifactVersion.InputReference requiredReference(
            JsonNode content,
            String field,
            String role,
            int order,
            Artifact.Kind expectedKind) {
        return new ArtifactVersion.InputReference(
                requireUuid(content, field), role, order, expectedKind);
    }

    private java.util.Optional<ArtifactVersion.InputReference> optionalReference(
            JsonNode content, String field, String role, Artifact.Kind expectedKind) {
        JsonNode value = content.get(field);
        if (value == null || value.isNull()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ArtifactVersion.InputReference(
                parseUuid(value, field), role, 0, expectedKind));
    }

    private void allowOnly(JsonNode content, String... allowedNames) {
        Set<String> allowed = Set.of(allowedNames);
        for (String propertyName : content.propertyNames()) {
            if (!allowed.contains(propertyName)) {
                throw invalid("content 包含不允许的字段：" + propertyName + "。");
            }
        }
    }

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

    private void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw invalid(field + " 必须是 JSON 对象。");
        }
    }

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

    private void optionalText(JsonNode content, String field, int maximum) {
        JsonNode value = content.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isString() || value.stringValue().length() > maximum) {
            throw invalid(field + " 必须是长度不超过 " + maximum + " 的字符串。");
        }
    }

    private void requireEnum(JsonNode content, String field, String... values) {
        String actual = requireText(content, field, 1, 80);
        if (!Set.of(values).contains(actual)) {
            throw invalid(field + " 不在允许的枚举值中。");
        }
    }

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

    private UUID requireUuid(JsonNode content, String field) {
        return parseUuid(content.get(field), field);
    }

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

    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "ARTIFACT_SCHEMA_INVALID",
                "产物内容无效",
                detail,
                false);
    }
}
