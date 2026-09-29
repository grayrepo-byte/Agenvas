package dev.agenvas.artifact.domain;

import dev.agenvas.shared.error.ApiProblemException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Card-level video controls frozen into every accepted output task. */
public record VideoGenerationParameters(String aspectRatio) {
    public static final String AUTO_ASPECT_RATIO = "AUTO";
    public static final String LANDSCAPE_ASPECT_RATIO = "16:9";
    public static final String PORTRAIT_ASPECT_RATIO = "9:16";
    public static final String SQUARE_ASPECT_RATIO = "1:1";
    public static final Set<String> ASPECT_RATIOS = Set.of(
            AUTO_ASPECT_RATIO, LANDSCAPE_ASPECT_RATIO, PORTRAIT_ASPECT_RATIO,
            SQUARE_ASPECT_RATIO);
    private static final Set<String> FIELDS = Set.of("aspectRatio");

    public static VideoGenerationParameters parse(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return new VideoGenerationParameters(AUTO_ASPECT_RATIO);
        }
        if (!input.isObject()) throw invalid("视频生成参数必须是对象。");
        for (String field : input.propertyNames()) {
            if (!FIELDS.contains(field)) throw invalid("视频生成参数包含未知字段：" + field + "。");
        }
        JsonNode value = input.get("aspectRatio");
        String aspectRatio = value == null ? AUTO_ASPECT_RATIO
                : value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
        if (aspectRatio == null || !ASPECT_RATIOS.contains(aspectRatio)) {
            throw invalid("视频比例必须为 AUTO、16:9、9:16 或 1:1。");
        }
        return new VideoGenerationParameters(aspectRatio);
    }

    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        result.put("aspectRatio", aspectRatio);
        return result;
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "视频生成参数无效", detail, false);
    }
}
