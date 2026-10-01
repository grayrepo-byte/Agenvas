package dev.agenvas.artifact.domain;

import dev.agenvas.shared.i18n.ApiMessage;
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
            throw invalid(ApiMessage.of("api.image-generation-parameters.image-generation-parameters-must-be-objects"));
        }
        for (String field : input.propertyNames()) {
            if (!FIELDS.contains(field)) throw invalid(ApiMessage.of("api.image-generation-parameters.image-generation-parameters-contain-an-unknown-field", field));
        }
        String aspectRatio = text(input, "aspectRatio", AUTO_ASPECT_RATIO);
        String resolution = text(input, "resolution", DEFAULT_RESOLUTION);
        String quality = text(input, "quality", DEFAULT_QUALITY);
        boolean transparent = bool(input, "transparentBackground", false);
        int count = integer(input, "generationCount", 1);
        if (!ASPECT_RATIOS.contains(aspectRatio)) throw invalid(ApiMessage.of("api.image-generation-parameters.unsupported-image-ratio"));
        if (!RESOLUTIONS.contains(resolution)) throw invalid(ApiMessage.of("api.image-generation-parameters.unsupported-image-resolution"));
        if (!QUALITIES.contains(quality)) throw invalid(ApiMessage.of("api.image-generation-parameters.image-quality-must-be-low-medium-or-high"));
        if (!GENERATION_COUNTS.contains(count)) throw invalid(ApiMessage.of("api.image-generation-parameters.the-number-of-image-generation-can-only-be-1-2"));
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
            throw invalid(ApiMessage.of("api.image-generation-parameters.the-selected-image-capability-does-not-support-aspect-ratio", aspectRatio));
        }
        if (!resolutions.contains(resolution)) {
            throw invalid(ApiMessage.of("api.image-generation-parameters.the-selected-image-capability-does-not-support-resolution", resolution));
        }
        if (!qualities.isEmpty() && !qualities.contains(quality)) {
            throw invalid(ApiMessage.of("api.image-generation-parameters.the-selected-image-capability-does-not-support-quality", quality));
        }
        if (transparentBackground && !supportsTransparentBackground) {
            throw invalid(ApiMessage.of("api.image-generation-parameters.the-selected-image-capability-does-not-support-transparent-backgrounds"));
        }
    }

    private static String text(JsonNode input, String field, String fallback) {
        JsonNode value = input.get(field);
        if (value == null) return fallback;
        if (!value.isTextual() || value.asText().isBlank()) throw invalid(ApiMessage.of("api.artifact-content-validator.must-be-a-string", field));
        return value.asText();
    }

    private static boolean bool(JsonNode input, String field, boolean fallback) {
        JsonNode value = input.get(field);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw invalid(ApiMessage.of("api.image-generation-parameters.must-be-a-boolean", field));
        return value.booleanValue();
    }

    private static int integer(JsonNode input, String field, int fallback) {
        JsonNode value = input.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid(ApiMessage.of("api.artifact-content-validator.must-be-an-integer", field));
        return value.intValue();
    }

    private static ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.image-generation-parameters.invalid-image-generation-parameters"), detail, false);
    }
}
