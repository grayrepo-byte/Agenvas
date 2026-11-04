package dev.agenvas.provider.domain;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Administrator rates per million reported Images API tokens; never a provider invoice. */
public final class ImageTokenPricing {
    private static final Set<String> REQUIRED = Set.of("currency", "textInputPerMillion", "imageInputPerMillion", "imageOutputPerMillion");
    private static final Set<String> FIELDS = Set.of("currency", "textInputPerMillion", "imageInputPerMillion", "imageOutputPerMillion",
            "cachedTextInputPerMillion", "cachedImageInputPerMillion");
    private ImageTokenPricing() {}

    public static ObjectNode normalize(ObjectMapper mapper, JsonNode source) {
        if (!source.isObject() || !source.propertyNames().containsAll(REQUIRED)
                || !FIELDS.containsAll(source.propertyNames()) || !Set.of("CNY", "USD").contains(source.path("currency").asText()))
            throw invalid();
        ObjectNode result = mapper.createObjectNode().put("currency", source.path("currency").asText());
        for (String field : source.propertyNames()) {
            if ("currency".equals(field)) continue;
            JsonNode value = source.get(field);
            if (!value.isTextual() || !value.asText().matches("[0-9]{1,10}(\\.[0-9]{1,6})?")) throw invalid();
            result.put(field, new BigDecimal(value.asText()).toPlainString());
        }
        return result;
    }

    /** Unknown cache counts retain the undiscounted estimate; positive ambiguous cache usage stays unknown. */
    public static BigDecimal estimate(JsonNode rates, JsonNode usage) {
        if (!usage.path("valid").asBoolean(false)) return null;
        Long text = count(usage, "textInputTokens"), image = count(usage, "imageInputTokens"),
                input = count(usage, "inputTokens"), output = count(usage, "outputTokens");
        if (text == null || image == null || input == null || output == null || text + image != input) return null;
        Long total = count(usage, "totalTokens"), imageOutput = count(usage, "imageOutputTokens"), textOutput = count(usage, "textOutputTokens");
        if (total != null && total != input + output || imageOutput != null && !imageOutput.equals(output)
                || textOutput != null && textOutput != 0) return null;
        Long cached = count(usage, "cachedInputTokens"), cachedText = count(usage, "cachedTextInputTokens"),
                cachedImage = count(usage, "cachedImageInputTokens");
        if (cached != null && cached > input) return null;
        if (cached != null && cached == 0 && cachedText == null && cachedImage == null) {
            cachedText = 0L; cachedImage = 0L;
        }
        boolean detailedCache = cachedText != null || cachedImage != null;
        if (detailedCache && (cachedText == null || cachedImage == null || cachedText > text || cachedImage > image
                || cached != null && cached != cachedText + cachedImage)) return null;
        if (!detailedCache && cached != null && cached > 0) return null;
        long textCached = cachedText == null ? 0 : cachedText, imageCached = cachedImage == null ? 0 : cachedImage;
        if (textCached > 0 && !rates.has("cachedTextInputPerMillion") || imageCached > 0 && !rates.has("cachedImageInputPerMillion")) return null;
        BigDecimal amount = cost(rates, "textInputPerMillion", text - textCached)
                .add(cost(rates, "imageInputPerMillion", image - imageCached))
                .add(cost(rates, "imageOutputPerMillion", output))
                .add(cost(rates, "cachedTextInputPerMillion", textCached))
                .add(cost(rates, "cachedImageInputPerMillion", imageCached))
                .movePointLeft(6).setScale(6, RoundingMode.HALF_UP);
        // Existing ledger amounts are numeric(18,6); oversized upstream counts must not prevent result archival.
        return amount.precision() <= 18 ? amount : null;
    }

    private static Long count(JsonNode usage, String field) {
        JsonNode value = usage.path(field);
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0 ? value.longValue() : null;
    }
    private static BigDecimal cost(JsonNode rates, String field, long tokens) {
        return tokens == 0 ? BigDecimal.ZERO : new BigDecimal(rates.path(field).asText()).multiply(BigDecimal.valueOf(tokens));
    }
    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_UNSUPPORTED_INPUT",
                ApiMessage.of("api.media-capability-configuration.invalid-media-configuration"),
                ApiMessage.of("api.media-capability-configuration.invalid-token-pricing"), false);
    }
}
