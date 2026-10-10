package dev.agenvas.provider.domain;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.math.BigDecimal;
import java.util.Set;
import java.util.List;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Versioned administrator defaults, prices and narrower limits within a compiled protocol. */
public final class MediaCapabilityConfiguration {
    public static final Set<String> FIELDS = Set.of("defaultParameters", "defaultDurationSeconds",
            "minimumSeconds", "maximumSeconds", "maxReferenceImages", "maxReferenceAudios", "maxReferenceVideos", "pricing", "pricingByResolution");
    private static final Set<String> PRICE_FIELDS = Set.of("amount", "currency", "unit");
    private static final Set<String> CURRENCIES = Set.of("CNY", "USD");
    private static final String PRICE_PATTERN = "[0-9]{1,10}(\\.[0-9]{1,6})?";

    private MediaCapabilityConfiguration() {}

    public static void normalize(ObjectMapper mapper, MediaAdapterRegistry.Declaration adapter,
            JsonNode source, ObjectNode target) {
        var policy = policy(adapter, source);
        for (String field : List.of("minimumSeconds", "maximumSeconds", "maxReferenceImages", "maxReferenceAudios", "maxReferenceVideos")) {
            if (source.has(field)) target.set(field, source.get(field));
        }
        if (source.has("defaultDurationSeconds")) {
            if (adapter.kind() != Task.Kind.VIDEO_GENERATION) throw invalid(ApiMessage.of("api.media-capability-configuration.picture-capabilities-do-not-accept-video-duration"));
            target.put("defaultDurationSeconds", integer(source, "defaultDurationSeconds",
                    policy.minimumSeconds(), policy.minimumSeconds(), policy.maximumSeconds()));
        }
        if (source.has("defaultParameters")) {
            JsonNode parameters = source.get("defaultParameters");
            if (!parameters.isObject()) throw invalid(ApiMessage.of("api.media-capability-configuration.default-build-parameters-must-be-objects"));
            if (adapter.kind() == Task.Kind.IMAGE_GENERATION) {
                ObjectNode imageParameters = (ObjectNode) parameters.deepCopy();
                if (!imageParameters.has("quality") && source.has("quality")) {
                    imageParameters.set("quality", source.get("quality"));
                }
                var parsed = ImageGenerationParameters.parse(imageParameters);
                parsed.requireSupported(adapter.supportedImageAspectRatios(),
                        adapter.supportedImageResolutions(), adapter.supportedImageQualities(),
                        adapter.supportsTransparentBackground());
                target.set("defaultParameters", parsed.toJson(mapper));
            } else if (adapter.kind() == Task.Kind.AUDIO_GENERATION) {
                target.set("defaultParameters", dev.agenvas.artifact.domain.AudioGenerationParameters.parse(parameters).toJson(mapper));
            } else {
                var parsed = VideoGenerationParameters.parse(parameters);
                if (adapter.platform() == MediaPlatform.MINIMAX) {
                    parsed = new VideoGenerationParameters(parsed.aspectRatio(), MiniMaxH3Protocol.resolution(parsed.videoResolution()));
                } else if (parsed.videoResolution() != null)
                    throw invalid(ApiMessage.of("api.auto-dl-workflows.this-workflow-does-not-support-this-resolution"));
                target.set("defaultParameters", parsed.toJson(mapper));
            }
        }
        if (source.has("pricing")) target.set("pricing", normalizePrice(mapper, adapter, source.get("pricing")));
        if (source.has("pricingByResolution")) {
            JsonNode prices = source.get("pricingByResolution");
            // Resolution prices are limited to protocols declaring selectable tiers.
            if (adapter.platform() != MediaPlatform.AUTODL && adapter.platform() != MediaPlatform.MINIMAX || adapter.kind() != Task.Kind.VIDEO_GENERATION || !prices.isObject())
                throw invalid(ApiMessage.of("api.auto-dl-workflows.this-workflow-does-not-support-this-resolution"));
            ObjectNode normalized = target.putObject("pricingByResolution");
            for (String tier : prices.propertyNames()) {
                if (adapter.platform() == MediaPlatform.MINIMAX) MiniMaxH3Protocol.resolution(tier);
                else AutoDlWorkflows.selectedResolution(target, tier);
                normalized.set(tier, normalizePrice(mapper, adapter, prices.get(tier)));
            }
        }
    }

    /** A resolution override wins; an absent override uses the optional uniform estimate. */
    public static JsonNode price(JsonNode settings, String resolution) {
        JsonNode tier = resolution == null ? null : settings.path("pricingByResolution").get(resolution);
        return tier == null ? settings.get("pricing") : tier;
    }

    private static ObjectNode normalizePrice(ObjectMapper mapper, MediaAdapterRegistry.Declaration adapter, JsonNode price) {
        if (!price.isObject() || !PRICE_FIELDS.equals(price.propertyNames()))
            throw invalid(ApiMessage.of("api.media-capability-configuration.price-must-contain-amount-currency-and-unit"));
        if (!price.path("amount").isTextual() || !price.path("amount").asText().matches(PRICE_PATTERN)
                || !CURRENCIES.contains(price.path("currency").asText()))
            throw invalid(ApiMessage.of("api.media-capability-configuration.price-should-be-a-non-negative-decimal-amount-up-to"));
        Set<String> units = adapter.kind() == Task.Kind.IMAGE_GENERATION ? Set.of("IMAGE")
                : adapter.kind() == Task.Kind.AUDIO_GENERATION ? Set.of("AUDIO", "SECOND") : Set.of("VIDEO", "SECOND");
        if (!units.contains(price.path("unit").asText()))
            throw invalid(ApiMessage.of("api.media-capability-configuration.price-unit-does-not-match-media-type"));
        return mapper.createObjectNode().put("amount", new BigDecimal(price.path("amount").asText()).toPlainString())
                .put("currency", price.path("currency").asText()).put("unit", price.path("unit").asText());
    }

    public static MediaAdapterRegistry.Declaration policy(MediaAdapterRegistry.Declaration adapter,
            JsonNode settings) {
        int minimum = integer(settings, "minimumSeconds", adapter.minimumSeconds(),
                adapter.minimumSeconds(), adapter.maximumSeconds());
        int maximum = integer(settings, "maximumSeconds", adapter.maximumSeconds(),
                minimum, adapter.maximumSeconds());
        int references = integer(settings, "maxReferenceImages", adapter.maxReferenceImages(),
                0, adapter.maxReferenceImages());
        return new MediaAdapterRegistry.Declaration(adapter.platform(), adapter.kind(), minimum,
                maximum, adapter.originRequired(), references, adapter.supportedVideoInputModes(),
                adapter.defaultVideoInputMode(), adapter.supportsEndFrame(),
                adapter.supportedImageAspectRatios(), adapter.supportedImageResolutions(),
                adapter.supportedImageQualities(), adapter.supportsTransparentBackground(),
                adapter.supportsImageMask(), integer(settings, "maxReferenceAudios", adapter.maxReferenceAudios(), 0, adapter.maxReferenceAudios()),
                integer(settings, "maxReferenceVideos", adapter.maxReferenceVideos(), 0, adapter.maxReferenceVideos()));
    }

    private static int integer(JsonNode source, String field, int fallback, int minimum, int maximum) {
        JsonNode value = source.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum) {
            throw invalid(ApiMessage.of("api.media-capability-configuration.must-be-between-and", field, minimum, maximum));
        }
        return value.intValue();
    }

    private static ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PROVIDER_UNSUPPORTED_INPUT", ApiMessage.of("api.media-capability-configuration.invalid-media-configuration"), detail, false);
    }
}
