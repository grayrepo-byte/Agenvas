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
            "minimumSeconds", "maximumSeconds", "maxReferenceImages", "maxReferenceAudios", "pricing");
    private static final Set<String> PRICE_FIELDS = Set.of("amount", "currency", "unit");
    private static final Set<String> CURRENCIES = Set.of("CNY", "USD");
    private static final String PRICE_PATTERN = "[0-9]{1,10}(\\.[0-9]{1,6})?";

    private MediaCapabilityConfiguration() {}

    public static void normalize(ObjectMapper mapper, MediaAdapterRegistry.Declaration adapter,
            JsonNode source, ObjectNode target) {
        var policy = policy(adapter, source);
        for (String field : List.of("minimumSeconds", "maximumSeconds", "maxReferenceImages", "maxReferenceAudios")) {
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
                target.set("defaultParameters", VideoGenerationParameters.parse(parameters).toJson(mapper));
            }
        }
        if (source.has("pricing")) {
            JsonNode price = source.get("pricing");
            if (!price.isObject() || !PRICE_FIELDS.equals(price.propertyNames())) {
                throw invalid(ApiMessage.of("api.media-capability-configuration.price-must-contain-amount-currency-and-unit"));
            }
            if (!price.path("amount").isTextual()
                    || !price.path("amount").asText().matches(PRICE_PATTERN)
                    || !CURRENCIES.contains(price.path("currency").asText())) {
                throw invalid(ApiMessage.of("api.media-capability-configuration.price-should-be-a-non-negative-decimal-amount-up-to"));
            }
            Set<String> units = adapter.kind() == Task.Kind.IMAGE_GENERATION
                    ? Set.of("IMAGE") : adapter.kind() == Task.Kind.AUDIO_GENERATION
                    ? Set.of("AUDIO", "SECOND") : Set.of("VIDEO", "SECOND");
            if (!units.contains(price.path("unit").asText())) throw invalid(ApiMessage.of("api.media-capability-configuration.price-unit-does-not-match-media-type"));
            ObjectNode normalized = target.putObject("pricing");
            normalized.put("amount", new BigDecimal(price.path("amount").asText()).toPlainString());
            normalized.put("currency", price.path("currency").asText());
            normalized.put("unit", price.path("unit").asText());
        }
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
                adapter.supportsImageMask(), integer(settings, "maxReferenceAudios", adapter.maxReferenceAudios(), 0, adapter.maxReferenceAudios()));
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
