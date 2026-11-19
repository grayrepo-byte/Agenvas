package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LlmModelPolicyTest {
    @Test
    void ollamaCannotAutomaticallyUseArchitecturalMaximumWhenUnloaded() {
        var metadata = new ModelCatalog.Model("model", 131072, null, null, null, null, null);
        assertThatThrownBy(() -> LlmModelPolicy.automatic(ModelCatalog.Provider.OLLAMA, metadata, 1024,
                LlmModelPolicy.OutputParameter.MAX_TOKENS)).hasMessage("MODEL_CAPACITY_UNKNOWN");
    }
    @Test
    void automaticLoadedContextIsConservativeAndPreservesTheory() {
        var metadata = new ModelCatalog.Model("model", 131072, null, null, 4096, null, null);
        var policy = LlmModelPolicy.automatic(ModelCatalog.Provider.OLLAMA, metadata, 1024,
                LlmModelPolicy.OutputParameter.MAX_TOKENS);
        assertThat(policy.contextWindowTokens()).isEqualTo(4096);
        assertThat(policy.theoreticalContextTokens()).isEqualTo(131072);
        assertThat(policy.runtimeContextTokens()).isEqualTo(4096);
        assertThat(policy.source()).isEqualTo(LlmModelPolicy.Source.OLLAMA_RUNTIME);
    }
    @Test
    void separateInputAndOutputLimitsRemainSeparate() {
        var metadata = new ModelCatalog.Model("model", null, 32000, 2048, null, null, null);
        var policy = LlmModelPolicy.automatic(ModelCatalog.Provider.GEMINI, metadata, 1024,
                LlmModelPolicy.OutputParameter.MAX_TOKENS);
        assertThat(policy.contextWindowTokens()).isNull();
        assertThat(policy.inputLimitTokens()).isEqualTo(32000);
        assertThat(policy.maxOutputTokens()).isEqualTo(1024);
        assertThatThrownBy(() -> LlmModelPolicy.automatic(ModelCatalog.Provider.GEMINI, metadata, 4096,
                LlmModelPolicy.OutputParameter.MAX_TOKENS)).hasMessage("MODEL_POLICY_INVALID");
    }
    @Test
    void manualValuesAreExplicitAndMustLeaveInputSpace() {
        var policy = LlmModelPolicy.manual(ModelCatalog.Provider.COMPATIBLE, 16384, null, 2048,
                LlmModelPolicy.OutputParameter.MAX_COMPLETION_TOKENS);
        assertThat(policy.source()).isEqualTo(LlmModelPolicy.Source.MANUAL);
        assertThat(policy.runtimeContextTokens()).isNull();
        assertThatThrownBy(() -> LlmModelPolicy.manual(ModelCatalog.Provider.COMPATIBLE, null, null, 1024,
                LlmModelPolicy.OutputParameter.MAX_TOKENS)).hasMessage("MODEL_CAPACITY_UNKNOWN");
        assertThatThrownBy(() -> LlmModelPolicy.manual(ModelCatalog.Provider.COMPATIBLE, 4096, null, 4096,
                LlmModelPolicy.OutputParameter.MAX_TOKENS)).hasMessage("MODEL_POLICY_INVALID");
    }
}
