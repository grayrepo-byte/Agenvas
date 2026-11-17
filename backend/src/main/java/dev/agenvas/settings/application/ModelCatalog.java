package dev.agenvas.settings.application;

import java.util.List;
import java.util.LinkedHashMap;
import tools.jackson.databind.JsonNode;

/** Safe, bounded metadata only; absent limits are not inferred from model names. */
public record ModelCatalog(List<Model> models, boolean partial) {
    public static final int MAX_MODELS = 2000;
    public static final int MAX_REPORTED_TOKENS = 16_000_000;
    public enum Provider {
        COMPATIBLE, OPENAI, OPENROUTER, GEMINI, ANTHROPIC, OLLAMA
    }
    public record Model(String id, Integer contextWindowTokens, Integer inputLimitTokens,
            Integer outputLimitTokens, Integer runtimeContextTokens, String digest, Integer deploymentContextTokens) {
    }
    public static ModelCatalog parse(Provider provider, JsonNode listed, JsonNode show,
            JsonNode running, String selectedModel) {
        JsonNode entries = listed.path(provider == Provider.GEMINI || provider == Provider.OLLAMA ? "models" : "data");
        if (!entries.isArray())
            throw new IllegalArgumentException("MODEL_METADATA_INVALID");
        var result = new LinkedHashMap<String, Model>();
        boolean partial = listed.path("has_more").asBoolean(false)
                || !listed.path("nextPageToken").asText("").isEmpty();
        for (JsonNode entry : entries) {
            if (result.size() >= MAX_MODELS) {
                partial = true;
                break;
            }
            String id = entry.path(provider == Provider.GEMINI || provider == Provider.OLLAMA ? "name" : "id")
                    .asText("");
            if (provider == Provider.GEMINI && id.startsWith("models/"))
                id = id.substring(7);
            if (!validModelId(id)) {
                partial = true;
                continue;
            }
            if (provider == Provider.GEMINI && entry.path("supportedGenerationMethods").isArray()) {
                boolean generate = false;
                for (JsonNode method : entry.path("supportedGenerationMethods"))
                    generate |= "generateContent".equals(method.asText());
                if (!generate)
                    continue;
            }
            Integer context = null, input = null, output = null, runtime = null, deployment = null;
            String digest = entry.path("digest").asText("");
            if (digest.length() > 160)
                digest = "";
            if (provider == Provider.GEMINI) {
                input = limit(entry.path("inputTokenLimit"));
                output = limit(entry.path("outputTokenLimit"));
            } else if (provider == Provider.ANTHROPIC) {
                input = limit(entry.path("max_input_tokens"));
                output = limit(entry.path("max_tokens"));
            } else if (provider == Provider.OPENROUTER) {
                context = limit(entry.path("context_length"));
                deployment = limit(entry.path("top_provider").path("context_length"));
                output = limit(entry.path("top_provider").path("max_completion_tokens"));
            } else if (provider == Provider.OLLAMA) {
                if (id.equals(selectedModel) && show != null) {
                    var info = show.path("model_info");
                    String architecture = info.path("general.architecture").asText("");
                    if (architecture.matches("[A-Za-z0-9_]{1,80}"))
                        context = limit(info.path(architecture + ".context_length"));
                }
                if (running != null)
                    for (JsonNode loaded : running.path("models")) {
                        if (!(id.equals(loaded.path("name").asText()) || id.equals(loaded.path("model").asText())))
                            continue;
                        String observedDigest = loaded.path("digest").asText("");
                        if (!digest.isEmpty() && !digest.equals(observedDigest))
                            continue;
                        runtime = limit(loaded.path("context_length"));
                        break;
                    }
            }
            result.putIfAbsent(id,
                    new Model(id, context, input, output, runtime, digest.isEmpty() ? null : digest, deployment));
        }
        return new ModelCatalog(List.copyOf(result.values()), partial);
    }

    public static boolean validModelId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}");
    }
    private static Integer limit(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0
                && value.intValue() <= MAX_REPORTED_TOKENS ? value.intValue() : null;
    }
}
