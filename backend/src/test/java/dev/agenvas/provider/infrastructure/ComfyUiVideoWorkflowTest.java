package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.project.domain.Project;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Candidate graph pins a real uploaded image into both Wan conditioning branches. */
class ComfyUiVideoWorkflowTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsExactFiveSecondShotToEightyOneFramesAndRootMp4() {
        ComfyUiVideoWorkflow workflow = new ComfyUiVideoWorkflow(models(), mapper);
        var graph = workflow.render("a moving coffee cup", "blurry", 42,
                "agenvas-pinned.png", Project.AspectRatio.LANDSCAPE_16_9, 5_000);
        assertThat(graph.path("1").path("inputs").path("image").asText())
                .isEqualTo("agenvas-pinned.png");
        assertThat(graph.path("6").path("inputs").path("image").get(0).asText())
                .isEqualTo("1");
        assertThat(graph.path("9").path("inputs").path("start_image").get(0).asText())
                .isEqualTo("1");
        assertThat(graph.path("9").path("inputs").path("clip_vision_output").get(0).asText())
                .isEqualTo("6");
        assertThat(graph.path("9").path("inputs").path("width").asInt()).isEqualTo(832);
        assertThat(graph.path("9").path("inputs").path("height").asInt()).isEqualTo(480);
        assertThat(graph.path("9").path("inputs").path("length").asInt()).isEqualTo(81);
        assertThat(graph.path("13").path("inputs").path("fps").asInt()).isEqualTo(16);
        assertThat(graph.path("14").path("inputs").path("format").asText())
                .isEqualTo("mp4");
        assertThat(workflow.version()).startsWith("image-to-video-v1-");
        assertThat(workflow.version()).isNotEqualTo(new ComfyUiVideoWorkflow(
                new ComfyUiVideoProperties( "another.safetensors",
                        "text.safetensors", "vae.safetensors", "vision.safetensors"),
                mapper).version());
    }

    @Test
    void rejectsUnsafeModelNameAndUnrepresentableShotDuration() {
        assertThatThrownBy(() -> new ComfyUiVideoWorkflow(
                new ComfyUiVideoProperties( "../private.safetensors",
                        "text.safetensors", "vae.safetensors", "vision.safetensors"),
                mapper)).isInstanceOf(IllegalArgumentException.class);
        ComfyUiVideoWorkflow workflow = new ComfyUiVideoWorkflow(models(), mapper);
        assertThat(workflow.supportsDuration(5_000)).isTrue();
        assertThat(workflow.supportsDuration(5_100)).isFalse();
        assertThat(workflow.supportsDurationSeconds(1)).isTrue();
        assertThat(workflow.supportsDurationSeconds(5)).isTrue();
        assertThat(workflow.supportsDurationSeconds(0)).isFalse();
        assertThat(workflow.supportsDurationSeconds(6)).isFalse();
        assertThat(workflow.renderSeconds("prompt", null, 1, "input.png",
                Project.AspectRatio.LANDSCAPE_16_9, 5)
                .path("9").path("inputs").path("length").asInt()).isEqualTo(81);
        assertThatThrownBy(() -> workflow.render("prompt", null, 1, "../other.png",
                Project.AspectRatio.LANDSCAPE_16_9, 5_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workflow.render("prompt", null, 1, "input.png",
                Project.AspectRatio.LANDSCAPE_16_9, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ComfyUiVideoProperties models() {
        return new ComfyUiVideoProperties( "wan2.1.safetensors",
                "text.safetensors", "vae.safetensors", "vision.safetensors");
    }
}
