package dev.agenvas.provider.domain;

import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Only bounded numeric usage and a safe reported model may leave an Images API response. */
public final class OpenAiImageUsage {
    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("inputTokens", "/input_tokens"), Map.entry("outputTokens", "/output_tokens"),
            Map.entry("totalTokens", "/total_tokens"), Map.entry("textInputTokens", "/input_tokens_details/text_tokens"),
            Map.entry("imageInputTokens", "/input_tokens_details/image_tokens"),
            Map.entry("cachedInputTokens", "/input_tokens_details/cached_tokens"),
            Map.entry("cachedTextInputTokens", "/input_tokens_details/cached_tokens_details/text_tokens"),
            Map.entry("cachedImageInputTokens", "/input_tokens_details/cached_tokens_details/image_tokens"),
            Map.entry("textOutputTokens", "/output_tokens_details/text_tokens"),
            Map.entry("imageOutputTokens", "/output_tokens_details/image_tokens"));
    public static final java.util.Set<String> COUNTS = PATHS.keySet();
    private OpenAiImageUsage() {}

    public static ObjectNode metadata(ObjectMapper mapper, JsonNode response) {
        JsonNode source = response.path("usage");
        if (!source.isObject()) return null;
        ObjectNode usage = mapper.createObjectNode();
        boolean valid = true;
        for (String path : java.util.List.of("/input_tokens_details", "/output_tokens_details", "/input_tokens_details/cached_tokens_details")) {
            JsonNode details = source.at(path);
            if (!details.isMissingNode() && !details.isNull() && !details.isObject()) valid = false;
        }
        for (var field : PATHS.entrySet()) {
            JsonNode value = source.at(field.getValue());
            if (value.isMissingNode() || value.isNull()) usage.putNull(field.getKey());
            else if (value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0) usage.put(field.getKey(), value.asInt());
            else { usage.putNull(field.getKey()); valid = false; }
        }
        usage.put("valid", valid);
        String model = response.path("model").asText("");
        if (model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}")) usage.put("model", model);
        return mapper.createObjectNode().set("imageTokenUsage", usage);
    }
}
