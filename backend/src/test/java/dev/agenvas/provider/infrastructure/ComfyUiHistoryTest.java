package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Fixed-node history parsing must not conflate pending, execution failure and safe output. */
class ComfyUiHistoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID promptId = UUID.randomUUID();

    @Test
    void emptyHistoryAndIncompleteEntryStayPendingForTheSamePrompt() {
        assertThat(ComfyUiHistory.image(mapper.createObjectNode(), promptId, "9"))
                .isInstanceOf(ComfyUiHistory.Pending.class);
        assertThat(ComfyUiHistory.image(history(false, "running", "image.png", "output", ""),
                promptId, "9")).isInstanceOf(ComfyUiHistory.Pending.class);
    }

    @Test
    void onlyTheTrustedNodeAndRootOutputFilenameCanCompleteAnImage() {
        assertThat(ComfyUiHistory.image(history(true, "success", "image.png", "output", ""),
                promptId, "9")).isEqualTo(new ComfyUiHistory.Ready("image.png"));
        assertThatThrownBy(() -> ComfyUiHistory.image(
                history(true, "success", "image.png", "output", ""), promptId, "8"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        assertThatThrownBy(() -> ComfyUiHistory.image(
                history(true, "success", "../secret.png", "output", ""), promptId, "9"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        assertThatThrownBy(() -> ComfyUiHistory.image(
                history(true, "success", "image.png", "input", ""), promptId, "9"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        assertThatThrownBy(() -> ComfyUiHistory.image(
                history(true, "success", "image.png", "output", "../other"), promptId, "9"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
    }

    @Test
    void terminalExecutionErrorIsNotAnEmptyHistoryOrDownloadCandidate() {
        assertThat(ComfyUiHistory.image(history(true, "error", "image.png", "output", ""),
                promptId, "9")).isInstanceOf(ComfyUiHistory.Failed.class);
        assertThat(ComfyUiHistory.image(history(false, "error", "image.png", "output", ""),
                promptId, "9")).isInstanceOf(ComfyUiHistory.Failed.class);
        assertThatThrownBy(() -> ComfyUiHistory.image(
                mapper.readTree("{\"" + UUID.randomUUID() + "\":{}}"), promptId, "9"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
    }

    @Test
    void videoRequiresAnimatedRootMp4FromTheFixedSaveVideoNode() {
        assertThat(ComfyUiHistory.video(mapper.createObjectNode(), promptId, "14"))
                .isInstanceOf(ComfyUiHistory.VideoPending.class);
        assertThat(ComfyUiHistory.video(videoHistory(false, "running", "clip.mp4", true),
                promptId, "14")).isInstanceOf(ComfyUiHistory.VideoPending.class);
        assertThat(ComfyUiHistory.video(videoHistory(true, "error", "clip.mp4", true),
                promptId, "14")).isInstanceOf(ComfyUiHistory.VideoFailed.class);
        assertThat(ComfyUiHistory.video(videoHistory(true, "success", "clip.mp4", true),
                promptId, "14")).isEqualTo(new ComfyUiHistory.VideoReady("clip.mp4"));
        assertThatThrownBy(() -> ComfyUiHistory.video(
                videoHistory(true, "success", "clip.mp4", false), promptId, "14"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        assertThatThrownBy(() -> ComfyUiHistory.video(
                videoHistory(true, "success", "clip.gif", true), promptId, "14"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        assertThatThrownBy(() -> ComfyUiHistory.video(
                videoHistory(true, "success", "../clip.mp4", true), promptId, "14"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
    }

    private tools.jackson.databind.JsonNode videoHistory(boolean complete, String status,
            String filename, boolean animated) {
        var root = mapper.createObjectNode();
        var entry = root.putObject(promptId.toString());
        entry.putObject("status").put("completed", complete).put("status_str", status);
        var output = entry.putObject("outputs").putObject("14");
        output.putArray("animated").add(animated);
        output.putArray("images").addObject().put("filename", filename)
                .put("type", "output").put("subfolder", "");
        return root;
    }

    private tools.jackson.databind.JsonNode history(boolean complete, String status,
            String filename, String type, String subfolder) {
        var root = mapper.createObjectNode();
        var entry = root.putObject(promptId.toString());
        entry.putObject("status").put("completed", complete).put("status_str", status);
        var image = entry.putObject("outputs").putObject("9").putArray("images")
                .addObject();
        image.put("filename", filename);
        image.put("type", type);
        image.put("subfolder", subfolder);
        return root;
    }
}
