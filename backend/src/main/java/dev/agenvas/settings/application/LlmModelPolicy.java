package dev.agenvas.settings.application;

import java.time.Instant;

/** Immutable confirmed limits, distinct from the provider's optional declarations. */
public record LlmModelPolicy(ModelCatalog.Provider provider, Source source,
        Integer contextWindowTokens, Integer inputLimitTokens, int maxOutputTokens,
        OutputParameter outputParameter, Integer theoreticalContextTokens,
        Integer reportedOutputTokens, Integer runtimeContextTokens, String modelDigest, Instant checkedAt) {
    public enum Source { MANUAL, PROVIDER_REPORTED, OLLAMA_RUNTIME }
    public enum OutputParameter { MAX_TOKENS, MAX_COMPLETION_TOKENS }
    public LlmModelPolicy {
        if (contextWindowTokens == null && inputLimitTokens == null)
            throw new IllegalArgumentException("MODEL_CAPACITY_UNKNOWN");
        if (provider == null || source == null || outputParameter == null
                || !valid(contextWindowTokens) || !valid(inputLimitTokens)
                || maxOutputTokens < 1 || maxOutputTokens > 2_000_000
                || (contextWindowTokens != null && maxOutputTokens >= contextWindowTokens)
                || (reportedOutputTokens != null && maxOutputTokens > reportedOutputTokens))
            throw new IllegalArgumentException("MODEL_POLICY_INVALID");
    }
    public static LlmModelPolicy automatic(ModelCatalog.Provider provider, ModelCatalog.Model model,
            int output, OutputParameter parameter) {
        Integer context = provider == ModelCatalog.Provider.OLLAMA ? model.runtimeContextTokens()
                : model.deploymentContextTokens() != null ? model.deploymentContextTokens() : model.contextWindowTokens();
        if (context != null && model.contextWindowTokens() != null) context = Math.min(context, model.contextWindowTokens());
        return new LlmModelPolicy(provider, provider == ModelCatalog.Provider.OLLAMA ? Source.OLLAMA_RUNTIME : Source.PROVIDER_REPORTED,
                context, model.inputLimitTokens(), output, parameter, model.contextWindowTokens(),
                model.outputLimitTokens(), model.runtimeContextTokens(), model.digest(), Instant.now());
    }
    public static LlmModelPolicy manual(ModelCatalog.Provider provider, Integer context, Integer input,
            int output, OutputParameter parameter) {
        return new LlmModelPolicy(provider, Source.MANUAL, context, input, output, parameter,
                null, null, null, null, null);
    }
    private static boolean valid(Integer value) {
        return value == null || value > 0 && value <= ModelCatalog.MAX_REPORTED_TOKENS;
    }
}
