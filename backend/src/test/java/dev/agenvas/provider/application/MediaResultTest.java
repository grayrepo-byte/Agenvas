package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.task.domain.Task;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MediaResultTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID assetId = UUID.randomUUID();

    @Test
    void capturesFixedWorkflowAndSourceTaskProvenance() {
        Task task = task(mapper.readTree("""
                {"prompt":"A landscape","negativePrompt":"blur",
                 "workflowVersion":"fixed-v2","mediaInput":{"parameters":{"resolution":"2K"}}}
                """));

        ObjectNode content = MediaResult.content(mapper, task, assetId,
                task.input().path("prompt").asText());

        assertThat(content.path("assetId").asText()).isEqualTo(assetId.toString());
        assertThat(content.path("prompt").asText()).isEqualTo("A landscape");
        assertThat(content.path("negativePrompt").asText()).isEqualTo("blur");
        assertThat(content.has("providerConfigVersion")).isFalse();
        assertThat(content.path("workflowVersion").asText()).isEqualTo("fixed-v2");
        assertThat(content.path("sourceTaskId").asText()).isEqualTo(task.id().toString());
        assertThat(content.path("parameters").isEmpty()).isTrue();
    }

    @Test
    void resultMetadataCanChangeWithoutMutatingNestedFrozenTaskParameters() {
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"mediaInput":{"parameters":{"adapterId":"frozen","nested":{"items":["original"]}}}}
                """);
        Task task = task(input);
        ObjectNode content = MediaResult.content(mapper, task, assetId, "");
        ObjectNode parameters = MediaResult.copyFrozenParameters(content, task);

        parameters.put("adapterId", "executed");
        parameters.withObject("nested").withArray("items").add("result-only");

        assertThat(content.has("negativePrompt")).isFalse();
        assertThat(content.path("parameters")).isSameAs(parameters);
        assertThat(input.path("mediaInput").path("parameters").path("adapterId").asText())
                .isEqualTo("frozen");
        assertThat(input.path("mediaInput").path("parameters").path("nested").path("items"))
                .hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"ignored\"", "false", "4", "{}"})
    void nonObjectParametersContributeNoFields(String source) {
        ObjectNode input = mapper.createObjectNode();
        input.putObject("mediaInput").set("parameters", mapper.readTree(source));
        Task task = task(input);
        ObjectNode content = MediaResult.content(mapper, task, assetId, "");

        assertThat(MediaResult.copyFrozenParameters(content, task).isEmpty()).isTrue();
        assertThat(input.path("mediaInput").path("parameters")).isEqualTo(mapper.readTree(source));
    }

    @Test
    void missingParameterObjectKeepsEmptyResultParameters() {
        Task task = task(mapper.createObjectNode());
        ObjectNode content = MediaResult.content(mapper, task, assetId, "");

        assertThat(MediaResult.copyFrozenParameters(content, task).isEmpty()).isTrue();
        assertThat(task.input().isEmpty()).isTrue();
    }

    private Task task(JsonNode input) {
        Task task = mock(Task.class);
        when(task.id()).thenReturn(UUID.randomUUID());
        when(task.input()).thenReturn(input);
        return task;
    }
}
