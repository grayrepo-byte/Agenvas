package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void terminalExecutionErrorIsNotAnEmptyHistoryOrDownloadCandidate(boolean complete) {
        assertThat(ComfyUiHistory.image(history(complete, "error", "image.png", "output", ""),
                promptId, "9")).isInstanceOf(ComfyUiHistory.Failed.class);
        assertThat(ComfyUiHistory.video(videoHistory(complete, "error", "clip.mp4", true),
                promptId, "14")).isInstanceOf(ComfyUiHistory.VideoFailed.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "[]", "\"history\"", "1", "true"})
    void bothMediaRequireAnObjectResponse(String responseJson) {
        JsonNode response = responseJson == null ? null : mapper.readTree(responseJson);
        assertThatThrownBy(() -> ComfyUiHistory.image(response, promptId, "9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exact prompt and template output node required");
        assertThatThrownBy(() -> ComfyUiHistory.video(response, promptId, "14"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exact prompt and video output node required");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "123456789", "-1", "1.0", "9 ", "node", "9/1"})
    void bothMediaRequireAFixedNumericOutputNode(String outputNodeId) {
        JsonNode response = mapper.createObjectNode();
        assertThatThrownBy(() -> ComfyUiHistory.image(response, promptId, outputNodeId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exact prompt and template output node required");
        assertThatThrownBy(() -> ComfyUiHistory.video(response, promptId, outputNodeId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exact prompt and video output node required");
    }

    @Test
    void bothMediaRequireThePersistedPromptId() {
        JsonNode response = mapper.createObjectNode();
        assertThatThrownBy(() -> ComfyUiHistory.image(response, null, "9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exact prompt and template output node required");
        assertThatThrownBy(() -> ComfyUiHistory.video(response, null, "14"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exact prompt and video output node required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"entry\"", "1", "true"})
    void malformedExactPromptEntriesAreProtocolFailures(String entryJson) {
        JsonNode response = mapper.createObjectNode()
                .set(promptId.toString(), mapper.readTree(entryJson));
        assertBothMediaReject(response, "History entry is malformed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"running\"", "1", "true", "{}",
            "{\"completed\":null}", "{\"completed\":1}", "{\"completed\":\"true\"}",
            "{\"completed\":[]}", "{\"completed\":{}}"})
    void malformedStatusesAndNonBooleanCompletionAreProtocolFailures(String statusJson) {
        var response = mapper.createObjectNode();
        response.putObject(promptId.toString()).set("status", mapper.readTree(statusJson));
        assertBothMediaReject(response, "History status is malformed");
    }

    @Test
    void missingStatusIsAProtocolFailureBeforeReadingOutputs() {
        var response = mapper.createObjectNode();
        response.putObject(promptId.toString());
        assertBothMediaReject(response, "History status is malformed");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "running", "unknown", "SUCCESS"})
    void completedHistoryRequiresTheSuccessStatus(String status) {
        assertBothMediaReject(history(true, status, "image.png", "output", ""),
                "Completed history has unknown status");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]"})
    void anotherPromptsHistoryCannotCompleteOrFailTheRequestedPrompt(String entryJson) {
        JsonNode response = mapper.createObjectNode()
                .set(UUID.randomUUID().toString(), mapper.readTree(entryJson));
        assertBothMediaReject(response, "History returned an unrelated prompt");
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

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "[]", "[true,true]", "[\"true\"]", "[false]"})
    void videoRequiresExactlyOneBooleanTrueAnimationFlag(String animatedJson) {
        JsonNode response = videoHistory(true, "success", "clip.mp4", true);
        ((ObjectNode) response.path(promptId.toString()).path("outputs").path("14"))
                .set("animated", mapper.readTree(animatedJson));
        assertThatThrownBy(() -> ComfyUiHistory.video(response, promptId, "14"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("Fixed video output is missing or ambiguous");
    }

    @ParameterizedTest
    @ValueSource(strings = {"clip.MP4", "clip.mp4.tmp", "clip..mp4", "folder/clip.mp4"})
    void videoRejectsUnsafeOrNonMp4Filenames(String filename) {
        assertThatThrownBy(() -> ComfyUiHistory.video(
                videoHistory(true, "success", filename, true), promptId, "14"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("Fixed MP4 output path is unsafe");
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "subfolder"})
    void videoRequiresTheRootOutputDirectory(String field) {
        JsonNode response = videoHistory(true, "success", "clip.mp4", true);
        ((ObjectNode) response.path(promptId.toString()).path("outputs").path("14")
                .path("images").get(0)).put(field, "input");
        assertThatThrownBy(() -> ComfyUiHistory.video(response, promptId, "14"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("Fixed MP4 output path is unsafe");
    }

    @Test
    void filenameLengthLimitsRemainSpecificToEachMedia() {
        String imageFilename = "a".repeat(160);
        String videoFilename = imageFilename + ".mp4";
        assertThat(ComfyUiHistory.image(history(true, "success", imageFilename, "output", ""),
                promptId, "9")).isEqualTo(new ComfyUiHistory.Ready(imageFilename));
        assertThat(ComfyUiHistory.video(videoHistory(true, "success", videoFilename, true),
                promptId, "14")).isEqualTo(new ComfyUiHistory.VideoReady(videoFilename));
        assertThatThrownBy(() -> ComfyUiHistory.image(
                history(true, "success", "a" + imageFilename, "output", ""), promptId, "9"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("Fixed image output path is unsafe");
        assertThatThrownBy(() -> ComfyUiHistory.video(
                videoHistory(true, "success", "a" + videoFilename, true), promptId, "14"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class)
                .hasMessage("Fixed MP4 output path is unsafe");
    }

    private void assertBothMediaReject(JsonNode response, String message) {
        assertThatThrownBy(() -> ComfyUiHistory.image(response, promptId, "9"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class).hasMessage(message);
        assertThatThrownBy(() -> ComfyUiHistory.video(response, promptId, "14"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class).hasMessage(message);
    }

    private JsonNode videoHistory(boolean complete, String status,
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

    private JsonNode history(boolean complete, String status,
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
