package dev.agenvas.artifact.domain;

import dev.agenvas.shared.error.ApiProblemException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Stable card-level image generation controls frozen into every accepted output task. */
public record ImageGenerationParameters(
        String aspectRatio,
        String resolution,
        String quality,
        boolean transparentBackground,
        int generationCount) {

    public static final String AUTO_ASPECT_RATIO = "AUTO";
    public static final String DEFAULT_RESOLUTION = "1K";
    public static final String DEFAULT_QUALITY = "medium";
    public static final Set<String> ASPECT_RATIOS = Set.of(
            AUTO_ASPECT_RATIO, "1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3",
            "21:9");
    public static final Set<String> RESOLUTIONS = Set.of("1K", "2K", "4K");
    public static final Set<String> QUALITIES = Set.of("low", "medium", "high");
    public static final Set<Integer> GENERATION_COUNTS = Set.of(1, 2, 4);
    private static final Set<String> FIELDS = Set.of(
            "aspectRatio", "resolution", "quality", "transparentBackground",
            "generationCount");

    public static ImageGenerationParameters parse(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return new ImageGenerationParameters(AUTO_ASPECT_RATIO, DEFAULT_RESOLUTION,
                    DEFAULT_QUALITY, false, 1);
        }
        if (!input.isObject()) {
            throw invalid("图片生成参数必须是对象。");
        }
        for (String field : input.propertyNames()) {
            if (!FIELDS.contains(field)) throw invalid("图片生成参数包含未知字段：" + field + "。");
        }
        String aspectRatio = text(input, "aspectRatio", AUTO_ASPECT_RATIO);
        String resolution = text(input, "resolution", DEFAULT_RESOLUTION);
        String quality = text(input, "quality", DEFAULT_QUALITY);
        boolean transparent = bool(input, "transparentBackground", false);
        int count = integer(input, "generationCount", 1);
        if (!ASPECT_RATIOS.contains(aspectRatio)) throw invalid("不支持的图片比例。");
        if (!RESOLUTIONS.contains(resolution)) throw invalid("不支持的图片分辨率。");
        if (!QUALITIES.contains(quality)) throw invalid("图片画质必须为 low、medium 或 high。");
        if (!GENERATION_COUNTS.contains(count)) throw invalid("图片生成数量只能为 1、2 或 4。");
        return new ImageGenerationParameters(aspectRatio, resolution, quality, transparent,
                count);
    }

    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        result.put("aspectRatio", aspectRatio);
        result.put("resolution", resolution);
        result.put("quality", quality);
        result.put("transparentBackground", transparentBackground);
        result.put("generationCount", generationCount);
        return result;
    }

    public void requireSupported(Set<String> aspectRatios, Set<String> resolutions,
            Set<String> qualities, boolean supportsTransparentBackground) {
        if (!aspectRatios.contains(aspectRatio)) {
            throw invalid("所选图片能力不支持比例 " + aspectRatio + "。");
        }
        if (!resolutions.contains(resolution)) {
            throw invalid("所选图片能力不支持分辨率 " + resolution + "。");
        }
        if (!qualities.isEmpty() && !qualities.contains(quality)) {
            throw invalid("所选图片能力不支持画质 " + quality + "。");
        }
        if (transparentBackground && !supportsTransparentBackground) {
            throw invalid("所选图片能力不支持透明背景。");
        }
    }

    private static String text(JsonNode input, String field, String fallback) {
        JsonNode value = input.get(field);
        if (value == null) return fallback;
        if (!value.isTextual() || value.asText().isBlank()) throw invalid(field + " 必须是字符串。");
        return value.asText();
    }

    private static boolean bool(JsonNode input, String field, boolean fallback) {
        JsonNode value = input.get(field);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw invalid(field + " 必须是布尔值。");
        return value.booleanValue();
    }

    private static int integer(JsonNode input, String field, int fallback) {
        JsonNode value = input.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid(field + " 必须是整数。");
        return value.intValue();
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "图片生成参数无效", detail, false);
    }
}
