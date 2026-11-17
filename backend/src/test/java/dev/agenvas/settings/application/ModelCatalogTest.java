package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ModelCatalogTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void compatibleListsDoNotInventCapacityOrTrustReturnedUrls() {
        var result = ModelCatalog.parse(ModelCatalog.Provider.COMPATIBLE, json.readTree("""
                {"data":[{"id":"tenant/model-a","url":"https://metadata.invalid/secret"},
                         {"id":"bad?key=secret"},{"id":"good","context_length":"1000000"}]}
                """), null, null, null);
        assertThat(result.models()).extracting(ModelCatalog.Model::id).containsExactly("tenant/model-a", "good");
        assertThat(result.models()).allSatisfy(model -> {
            assertThat(model.contextWindowTokens()).isNull();
            assertThat(model.inputLimitTokens()).isNull();
            assertThat(model.outputLimitTokens()).isNull();
        });
        assertThat(result.partial()).isTrue();
    }

    @Test
    void providerInputLimitsAreNotRelabelledAsCombinedContext() {
        var gemini = ModelCatalog.parse(ModelCatalog.Provider.GEMINI, json.readTree("""
                {"models":[{"name":"models/gemini-synthetic","inputTokenLimit":32000,"outputTokenLimit":4096,
                 "supportedGenerationMethods":["generateContent"]},
                 {"name":"models/embedding","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"next"}
                """), null, null, null);
        assertThat(gemini.models()).hasSize(1);
        var model = gemini.models().getFirst();
        assertThat(model.id()).isEqualTo("gemini-synthetic");
        assertThat(model.contextWindowTokens()).isNull();
        assertThat(model.inputLimitTokens()).isEqualTo(32000);
        assertThat(model.outputLimitTokens()).isEqualTo(4096);
        assertThat(gemini.partial()).isTrue();
        var claude = ModelCatalog.parse(ModelCatalog.Provider.ANTHROPIC, json.readTree("""
                {"data":[{"id":"claude-synthetic","max_input_tokens":64000,"max_tokens":8192}]}
                """), null, null, null).models().getFirst();
        assertThat(claude.contextWindowTokens()).isNull();
        assertThat(claude.inputLimitTokens()).isEqualTo(64000);
        assertThat(claude.outputLimitTokens()).isEqualTo(8192);
    }

    @Test
    void openRouterUsesRoutingLimitsInsteadOfPromotingModelMaximum() {
        var result = ModelCatalog.parse(ModelCatalog.Provider.OPENROUTER, json.readTree("""
                {"data":[{"id":"provider/model","context_length":128000,
                 "top_provider":{"context_length":32000,"max_completion_tokens":4096}}]}
                """), null, null, null);
        assertThat(result.models()).hasSize(1);
        var model = result.models().getFirst();
        assertThat(model.contextWindowTokens()).isEqualTo(128000);
        assertThat(model.deploymentContextTokens()).isEqualTo(32000);
        assertThat(model.runtimeContextTokens()).isNull();
        assertThat(model.outputLimitTokens()).isEqualTo(4096);
    }

    @Test
    void ollamaTheoryAndCurrentAllocationStaySeparateAndDigestMustMatch() {
        var tags = json.readTree("""
                {"models":[{"name":"model:latest","digest":"digest-a"},{"name":"unloaded:latest"}]}
                """);
        var show = json.readTree("""
                {"model_info":{"general.architecture":"synthetic","synthetic.context_length":131072},
                 "parameters":"num_ctx 8192"}
                """);
        var ps = json.readTree("""
                {"models":[{"name":"model:latest","digest":"digest-a","context_length":4096}]}
                """);
        var result = ModelCatalog.parse(ModelCatalog.Provider.OLLAMA, tags, show, ps, "model:latest");
        assertThat(result.models()).hasSize(2);
        assertThat(result.models().getFirst().contextWindowTokens()).isEqualTo(131072);
        assertThat(result.models().getFirst().runtimeContextTokens()).isEqualTo(4096);
        assertThat(result.models().get(1).runtimeContextTokens()).isNull();
        var changed = ModelCatalog.parse(ModelCatalog.Provider.OLLAMA, tags, show,
                json.readTree(
                        "{\"models\":[{\"name\":\"model:latest\",\"digest\":\"other\",\"context_length\":64000}]}"),
                "model:latest");
        assertThat(changed.models().getFirst().runtimeContextTokens()).isNull();
    }

    @Test
    void invalidAndAbsentNumbersStayUnknownAndDuplicateModelsAreStable() {
        var result = ModelCatalog.parse(ModelCatalog.Provider.ANTHROPIC, json.readTree("""
                {"data":[{"id":"model","max_input_tokens":0,"max_tokens":-1},
                         {"id":"model","max_input_tokens":9999},{"id":"other","max_input_tokens":1e100}]}
                """), null, null, null);
        assertThat(result.models()).hasSize(2);
        assertThat(result.models().getFirst().inputLimitTokens()).isNull();
        assertThat(result.models().getFirst().outputLimitTokens()).isNull();
        assertThat(result.models().get(1).inputLimitTokens()).isNull();
    }
}
