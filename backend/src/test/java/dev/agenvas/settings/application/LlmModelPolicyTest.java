package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LlmModelPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test
    void ollamaCannotAutomaticallyUseArchitecturalMaximumWhenUnloaded() {
        var metadata = ModelCatalog.parse(ModelCatalog.Provider.OLLAMA,
                json.readTree("{\"models\":[{\"name\":\"model\"}]}"),
                json.readTree("{\"model_info\":{\"general.architecture\":\"x\",\"x.context_length\":131072}}"),
                json.readTree("{\"models\":[]}"), "model").models().getFirst();
        assertThatThrownBy(() -> LlmModelPolicy.automatic(ModelCatalog.Provider.OLLAMA, metadata, 1024,
                LlmModelPolicy.OutputParameter.MAX_TOKENS)).hasMessage("MODEL_CAPACITY_UNKNOWN");
    }
    @Test
    void automaticLoadedContextIsConservativeAndPreservesTheory() {
        var metadata = ModelCatalog.parse(ModelCatalog.Provider.OLLAMA,
                json.readTree("{\"models\":[{\"name\":\"model\"}]}"),
                json.readTree("{\"model_info\":{\"general.architecture\":\"x\",\"x.context_length\":131072}}"),
                json.readTree("{\"models\":[{\"name\":\"model\",\"context_length\":4096}]}"), "model").models()
                .getFirst();
        var policy = LlmModelPolicy.automatic(ModelCatalog.Provider.OLLAMA, metadata, 1024,
                LlmModelPolicy.OutputParameter.MAX_TOKENS);
        assertThat(policy.contextWindowTokens()).isEqualTo(4096);
        assertThat(policy.theoreticalContextTokens()).isEqualTo(131072);
        assertThat(policy.runtimeContextTokens()).isEqualTo(4096);
        assertThat(policy.source()).isEqualTo(LlmModelPolicy.Source.OLLAMA_RUNTIME);
    }
    @Test
    void separateInputAndOutputLimitsRemainSeparate() {
        var metadata = ModelCatalog.parse(ModelCatalog.Provider.GEMINI,
                json.readTree(
                        "{\"models\":[{\"name\":\"models/model\",\"inputTokenLimit\":32000,\"outputTokenLimit\":2048}]}"),
                null, null, null).models().getFirst();
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
