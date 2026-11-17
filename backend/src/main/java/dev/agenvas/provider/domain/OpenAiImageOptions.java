package dev.agenvas.provider.domain;

import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Fixed Images API options, pinned by the immutable capability version. */
public record OpenAiImageOptions(String outputFormat, String complexity, int frameCount, int frameDurationMs,
        String moderation) {
    public static final String FLARE = "gpt-image-2.6-flare";
    public static final String SUNBURST = "gpt-image-2.6-sunburst";
    public static final Set<String> MODELS_26 = Set.of(FLARE, SUNBURST);
    public static final Set<String> EXTENDED_QUALITIES = Set.of("low", "medium", "high", "xhigh", "max");
    private static final Set<String> MODELS_25 = Set.of("gpt-image-2.5-flare", "gpt-image-2.5-sunburst",
            "gpt-image-2.5-flare-2026-09-08", "gpt-image-2.5-sunburst-2026-09-08");

    /** Missing options keep the historical wire shape; OpenAI's omitted default is auto. */
    public OpenAiImageOptions(String outputFormat, String complexity, int frameCount, int frameDurationMs) {
        this(outputFormat, complexity, frameCount, frameDurationMs, null);
    }

    public OpenAiImageOptions {
        requireModeration(moderation);
    }

    public static void requireModeration(String value) {
        if (value != null && !Set.of("auto", "low").contains(value))
            throw invalid();
    }

    public static Set<String> qualities(String model) {
        return MODELS_26.contains(model) || MODELS_25.contains(model)
                ? EXTENDED_QUALITIES
                : ImageGenerationParameters.QUALITIES;
    }

    public static boolean supportsMarkup(JsonNode settings) {
        return "png".equals(settings.path("outputFormat").asText("png"))
                && supportsMarkupModel(settings.path("model").asText(""));
    }

    public static boolean supportsMarkupModel(String model) {
        return MODELS_26.contains(model) || MODELS_25.contains(model);
    }

    public boolean animated() {
        return "gif".equals(outputFormat);
    }

    public static OpenAiImageOptions parse(JsonNode settings) {
        String model = settings.path("model").asText("");
        String format = text(settings, "outputFormat", "png");
        String complexity = settings.has("complexity") ? text(settings, "complexity", null) : null;
        String moderation = settings.has("moderation") ? text(settings, "moderation", null) : null;
        if (!Set.of("png", "gif").contains(format)
                || complexity != null
                        && (!MODELS_26.contains(model) || !Set.of("low", "medium", "high").contains(complexity)))
            throw invalid();
        if ("gif".equals(format)) {
            if (!MODELS_26.contains(model))
                throw invalid();
            int frames = integer(settings, "frameCount", 9, 2, 16);
            int duration = integer(settings, "frameDurationMs", 200, 20, 2000);
            if (duration % 10 != 0)
                throw invalid();
            return new OpenAiImageOptions(format, complexity, frames, duration, moderation);
        }
        if (settings.has("frameCount") || settings.has("frameDurationMs"))
            throw invalid();
        return new OpenAiImageOptions(format, complexity, 0, 0, moderation);
    }

    public static void normalize(JsonNode source, ObjectNode target) {
        OpenAiImageOptions options = parse(source);
        if (source.has("outputFormat"))
            target.put("outputFormat", options.outputFormat());
        if (options.complexity() != null)
            target.put("complexity", options.complexity());
        if (options.moderation() != null)
            target.put("moderation", options.moderation());
        if (options.animated())
            target.put("frameCount", options.frameCount()).put("frameDurationMs", options.frameDurationMs());
    }

    public static MediaAdapterRegistry.Declaration declaration(MediaAdapterRegistry.Declaration base,
            JsonNode settings) {
        OpenAiImageOptions options = parse(settings);
        return new MediaAdapterRegistry.Declaration(base.platform(), base.kind(), 0, 0, base.originRequired(),
                options.animated() ? 0 : base.maxReferenceImages(), base.supportedVideoInputModes(),
                base.defaultVideoInputMode(), base.supportsEndFrame(),
                options.animated() ? Set.of("AUTO") : base.supportedImageAspectRatios(),
                options.animated() ? Set.of("1K") : base.supportedImageResolutions(),
                qualities(settings.path("model").asText("")),
                !options.animated() && base.supportsTransparentBackground(),
                !options.animated() && base.supportsImageMask(), 0, 0);
    }

    private static String text(JsonNode source, String field, String fallback) {
        if (!source.has(field))
            return fallback;
        if (!source.path(field).isTextual())
            throw invalid();
        return source.path(field).asText();
    }

    private static int integer(JsonNode source, String field, int fallback, int minimum, int maximum) {
        if (!source.has(field))
            return fallback;
        JsonNode value = source.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum
                || value.intValue() > maximum)
            throw invalid();
        return value.intValue();
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_UNSUPPORTED_INPUT",
                ApiMessage.of("api.media-capability-configuration.invalid-media-configuration"),
                ApiMessage.of("api.openai-image.invalid-options"), false);
    }
}
