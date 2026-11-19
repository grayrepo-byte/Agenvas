package dev.agenvas.settings.application;

import java.util.List;

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
    public static boolean validModelId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}");
    }
}
