package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Bundled image-v1 graph preserves the only permitted image-conditioning path. */
class ComfyUiImageWorkflowTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void changesOnlyApprovedSlotsAndPinsReferenceIntoSamplerInput() {
        ComfyUiImageWorkflow workflow = new ComfyUiImageWorkflow(
                new ComfyUiImageProperties("installed-model.safetensors"), mapper);
        var graph = workflow.render("A cafe", "blurred", 42,
                "agenvas-reference.png", true);
        assertThat(graph.path("1").path("inputs").path("image").asText())
                .isEqualTo("agenvas-reference.png");
        assertThat(graph.path("2").path("inputs").path("ckpt_name").asText())
                .isEqualTo("installed-model.safetensors");
        assertThat(graph.path("3").path("inputs").path("text").asText())
                .isEqualTo("A cafe");
        assertThat(graph.path("6").path("inputs").path("denoise").doubleValue())
                .isEqualTo(0.65);
        assertThat(graph.path("5").path("inputs").path("pixels").get(0).asText())
                .isEqualTo("1");
        assertThat(graph.path("6").path("inputs").path("latent_image").get(0).asText())
                .isEqualTo("5");
        assertThat(graph.path("8").path("inputs").path("images").get(0).asText())
                .isEqualTo("7");
        assertThat(workflow.render("Another", null, 9, "blank.png", false)
                .path("6").path("inputs").path("denoise").doubleValue()).isEqualTo(1.0);
        assertThat(workflow.version()).startsWith("image-v1-");
        assertThat(workflow.version()).hasSize(41);
    }

    @Test
    void forbidsUnsafeOperatorModelAndUntrustedPerTaskInput() {
        assertThatThrownBy(() -> new ComfyUiImageWorkflow(
                new ComfyUiImageProperties("../private.safetensors"), mapper))
                .isInstanceOf(IllegalArgumentException.class);
        ComfyUiImageWorkflow workflow = new ComfyUiImageWorkflow(
                new ComfyUiImageProperties("installed-model.safetensors"), mapper);
        assertThatThrownBy(() -> workflow.render("prompt", "", 1,
                "../other.png", true)).isInstanceOf(IllegalArgumentException.class);
    }
}
