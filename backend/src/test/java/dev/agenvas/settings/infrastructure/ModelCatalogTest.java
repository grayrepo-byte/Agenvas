package dev.agenvas.settings.infrastructure;

import dev.agenvas.settings.application.ModelCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

class ModelCatalogTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void compatibleListsDoNotInventCapacityOrTrustReturnedUrls() {
        var result = ModelMetadataProtocol.forProvider(ModelCatalog.Provider.COMPATIBLE).parse(json.readTree("""
                {"data":[{"id":"tenant/model-a","url":"https://metadata.invalid/secret"},
                         {"id":"bad?key=secret"},{"id":"good","context_length":"1000000"}]}
                """));
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
        var gemini = ModelMetadataProtocol.forProvider(ModelCatalog.Provider.GEMINI).parse(json.readTree("""
                {"models":[{"name":"models/gemini-synthetic","inputTokenLimit":32000,"outputTokenLimit":4096,
                 "supportedGenerationMethods":["generateContent"]},
                 {"name":"models/embedding","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"next"}
                """));
        assertThat(gemini.models()).hasSize(1);
        var model = gemini.models().getFirst();
        assertThat(model.id()).isEqualTo("gemini-synthetic");
        assertThat(model.contextWindowTokens()).isNull();
        assertThat(model.inputLimitTokens()).isEqualTo(32000);
        assertThat(model.outputLimitTokens()).isEqualTo(4096);
        assertThat(gemini.partial()).isTrue();
        var claude = ModelMetadataProtocol.forProvider(ModelCatalog.Provider.ANTHROPIC).parse(json.readTree("""
                {"data":[{"id":"claude-synthetic","max_input_tokens":64000,"max_tokens":8192}]}
                """)).models().getFirst();
        assertThat(claude.contextWindowTokens()).isNull();
        assertThat(claude.inputLimitTokens()).isEqualTo(64000);
        assertThat(claude.outputLimitTokens()).isEqualTo(8192);
    }

    @Test
    void openRouterUsesRoutingLimitsInsteadOfPromotingModelMaximum() {
        var result = ModelMetadataProtocol.forProvider(ModelCatalog.Provider.OPENROUTER).parse(json.readTree("""
                {"data":[{"id":"provider/model","context_length":128000,
                 "top_provider":{"context_length":32000,"max_completion_tokens":4096}}]}
                """));
        assertThat(result.models()).hasSize(1);
        var model = result.models().getFirst();
        assertThat(model.contextWindowTokens()).isEqualTo(128000);
        assertThat(model.deploymentContextTokens()).isEqualTo(32000);
        assertThat(model.runtimeContextTokens()).isNull();
        assertThat(model.outputLimitTokens()).isEqualTo(4096);
    }

    @Test
    void ollamaTheoryAndCurrentAllocationStaySeparateAndDigestMustMatch() throws Exception {
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
        var result = ollamaCatalog(tags, show, ps);
        assertThat(result.models()).hasSize(2);
        assertThat(result.models().getFirst().contextWindowTokens()).isEqualTo(131072);
        assertThat(result.models().getFirst().runtimeContextTokens()).isEqualTo(4096);
        assertThat(result.models().get(1).runtimeContextTokens()).isNull();
        var changed = ollamaCatalog(tags, show,
                json.readTree(
                        "{\"models\":[{\"name\":\"model:latest\",\"digest\":\"other\",\"context_length\":64000}]}"));
        assertThat(changed.models().getFirst().runtimeContextTokens()).isNull();
    }

    @Test
    void pagedCatalogStopsAtFourPagesOrTwoThousandModels() throws Exception {
        var protocol = ModelMetadataProtocol.forProvider(ModelCatalog.Provider.GEMINI);
        for (int modelsPerPage : java.util.List.of(1, 1200)) {
            var pages = new java.util.concurrent.atomic.AtomicInteger();
            var catalog = protocol.fetch("/v1beta", null, "synthetic-key", (path, cursor, body) -> {
                int number = pages.incrementAndGet();
                var page = json.createObjectNode().put("nextPageToken", "page-" + number);
                var models = page.putArray("models");
                for (int row = 0; row < modelsPerPage; row++)
                    models.addObject().put("name", "models/model-" + number + "-" + row);
                return page;
            });
            assertThat(pages.get()).isEqualTo(modelsPerPage == 1 ? 4 : 2);
            assertThat(catalog.models()).hasSize(modelsPerPage == 1 ? 4 : 2000);
            assertThat(catalog.partial()).isTrue();
        }
    }

    private ModelCatalog ollamaCatalog(JsonNode tags, JsonNode show, JsonNode ps) throws java.io.IOException {
        return ModelMetadataProtocol.forProvider(ModelCatalog.Provider.OLLAMA).fetch("", "model:latest",
                "synthetic-key",
                (path, cursor, body) -> switch (path) {
                    case "/api/tags" -> tags;
                    case "/api/ps" -> ps;
                    case "/api/show" -> show;
                    default -> throw new AssertionError(path);
                });
    }

    @Test
    void invalidAndAbsentNumbersStayUnknownAndDuplicateModelsAreStable() {
        var result = ModelMetadataProtocol.forProvider(ModelCatalog.Provider.ANTHROPIC).parse(json.readTree("""
                {"data":[{"id":"model","max_input_tokens":0,"max_tokens":-1},
                         {"id":"model","max_input_tokens":9999},{"id":"other","max_input_tokens":1e100}]}
                """));
        assertThat(result.models()).hasSize(2);
        assertThat(result.models().getFirst().inputLimitTokens()).isNull();
        assertThat(result.models().getFirst().outputLimitTokens()).isNull();
        assertThat(result.models().get(1).inputLimitTokens()).isNull();
    }
}
