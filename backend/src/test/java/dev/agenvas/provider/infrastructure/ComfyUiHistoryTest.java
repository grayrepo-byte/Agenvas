package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import dev.agenvas.provider.domain.ComfyUiWorkflowDefinition;
import dev.agenvas.task.domain.Task;
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
    private static final String SYNTHETIC_PROXY_PROMPT_ID = "2100000000000000123";
    private final String promptId = SYNTHETIC_PROXY_PROMPT_ID;

    @Test void publishedOutputUsesSelectedNodeAndSafeSubfolder() {
        var output = new ComfyUiWorkflowDefinition.Output("9", "images");
        assertThat(ComfyUiHistory.published(history(true, "success", "image.png", "output", "render/day"), promptId, output, Task.Kind.IMAGE_GENERATION))
                .isEqualTo(new ComfyUiHistory.PublishedReady("image.png", "render/day", "image/png"));
        assertThatThrownBy(() -> ComfyUiHistory.published(history(true, "success", "image.png", "output", "render/day"), promptId,
                new ComfyUiWorkflowDefinition.Output("8", "images"), Task.Kind.IMAGE_GENERATION)).isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        assertThat(ComfyUiHistory.published(mapper.createObjectNode(), promptId, output, Task.Kind.IMAGE_GENERATION))
                .isInstanceOf(ComfyUiHistory.PublishedPending.class);
        assertThat(ComfyUiHistory.published(history(false, "error", "image.png", "output", ""), promptId, output, Task.Kind.IMAGE_GENERATION))
                .isInstanceOf(ComfyUiHistory.PublishedFailed.class);
    }

    @Test void publishedOutputAcceptsSuccessfulProxyHistoryWithoutCompletedFlag() {
        var output = new ComfyUiWorkflowDefinition.Output("9", "images");
        JsonNode response = history(true, "success", "image.png", "output", "render/day");
        ((ObjectNode) response.path(promptId).path("status")).remove("completed");

        assertThat(ComfyUiHistory.published(response, promptId, output, Task.Kind.IMAGE_GENERATION))
                .isEqualTo(new ComfyUiHistory.PublishedReady("image.png", "render/day", "image/png"));
    }

    @Test void publishedOutputRejectsTraversalAndUnexpectedTypes() {
        var output = new ComfyUiWorkflowDefinition.Output("9", "images");
        for (String folder : java.util.List.of("../private", "/absolute", "a/../b", "a\\b", "a//b", "a/%2e%2e"))
            assertThatThrownBy(() -> ComfyUiHistory.published(history(true, "success", "image.png", "output", folder), promptId, output, Task.Kind.IMAGE_GENERATION))
                    .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        assertThatThrownBy(() -> ComfyUiHistory.published(history(true, "success", "image.png", "input", ""), promptId, output, Task.Kind.IMAGE_GENERATION)).isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        assertThatThrownBy(() -> ComfyUiHistory.published(history(true, "success", "image.png", "output", ""), promptId, output, Task.Kind.VIDEO_GENERATION)).isInstanceOf(ComfyUiClient.ProtocolFailure.class);
    }

    @Test void publishedOutputSelectsFirstSafeMatchingMediaInProviderOrder() {
        var response = history(true, "success", "preview.png", "temp", "");
        var files = (tools.jackson.databind.node.ArrayNode) response.at("/" + promptId + "/outputs/9/images");
        files.addObject().put("filename", "../unsafe.mp4").put("type", "output");
        files.addObject().put("filename", "image.png").put("type", "output");
        files.addObject().put("filename", "first.mp4").put("type", "output").put("subfolder", "render/day");
        files.addObject().put("filename", "second.mp4").put("type", "output");
        assertThat(ComfyUiHistory.published(response, promptId,
                new ComfyUiWorkflowDefinition.Output("9", "images"), Task.Kind.VIDEO_GENERATION))
                .isEqualTo(new ComfyUiHistory.PublishedReady("first.mp4", "render/day", "video/mp4"));
        assertThat(ComfyUiHistory.published(response, promptId,
                new ComfyUiWorkflowDefinition.Output("9", "images"), Task.Kind.IMAGE_GENERATION))
                .isEqualTo(new ComfyUiHistory.PublishedReady("image.png", "", "image/png"));
    }

    @Test void fixedImageSelectsFirstSafeOutputAndIgnoresPreviewsAndExtras() {
        var response = history(true, "success", "preview.png", "temp", "");
        var files = (tools.jackson.databind.node.ArrayNode) response.at("/" + promptId + "/outputs/9/images");
        files.addObject().put("filename", "../unsafe.png").put("type", "output");
        files.addObject().put("filename", "clip.mp4").put("type", "output");
        files.addObject().put("filename", "first.png").put("type", "output");
        files.addObject().put("filename", "second.png").put("type", "output");
        assertThat(ComfyUiHistory.image(response, promptId, "9"))
                .isEqualTo(new ComfyUiHistory.Ready("first.png"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[true]", "[false,true,true]"})
    void fixedVideoSelectsFirstSafeMp4WithSharedOrPerFileAnimationFlags(String flags) {
        var response = videoHistory(true, "success", "preview.png", true);
        var output = (ObjectNode) response.at("/" + promptId + "/outputs/14");
        var files = (tools.jackson.databind.node.ArrayNode) output.path("images");
        files.addObject().put("filename", "first.mp4").put("type", "output");
        files.addObject().put("filename", "second.mp4").put("type", "output");
        output.set("animated", mapper.readTree(flags));
        assertThat(ComfyUiHistory.video(response, promptId, "14"))
                .isEqualTo(new ComfyUiHistory.VideoReady("first.mp4"));
    }

    @Test void fixedVideoSkipsNonAnimatedAndUnsafeMp4Candidates() {
        var response = videoHistory(true, "success", "still.mp4", false);
        var output = (ObjectNode) response.at("/" + promptId + "/outputs/14");
        var files = (tools.jackson.databind.node.ArrayNode) output.path("images");
        files.addObject().put("filename", "../unsafe.mp4").put("type", "output");
        files.addObject().put("filename", "preview.mp4").put("type", "temp");
        files.addObject().put("filename", "first.mp4").put("type", "output");
        output.set("animated", mapper.readTree("[false,true,true,true]"));
        assertThat(ComfyUiHistory.video(response, promptId, "14"))
                .isEqualTo(new ComfyUiHistory.VideoReady("first.mp4"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]"})
    void absentOrEmptyOutputCollectionsStillCannotComplete(String filesJson) {
        var image = history(true, "success", "image.png", "output", "");
        ((ObjectNode) image.at("/" + promptId + "/outputs/9")).set("images", mapper.readTree(filesJson));
        assertThatThrownBy(() -> ComfyUiHistory.image(image, promptId, "9"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        assertThatThrownBy(() -> ComfyUiHistory.published(image, promptId,
                new ComfyUiWorkflowDefinition.Output("9", "images"), Task.Kind.IMAGE_GENERATION))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
        var video = videoHistory(true, "success", "clip.mp4", true);
        ((ObjectNode) video.at("/" + promptId + "/outputs/14")).set("images", mapper.readTree(filesJson));
        assertThatThrownBy(() -> ComfyUiHistory.video(video, promptId, "14"))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class);
    }

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

    @Test
    void bothLegacyMediaAcceptSuccessfulProxyHistoryWithoutCompletedFlag() {
        JsonNode imageResponse = withoutCompleted(history(true, "success", "image.png", "output", ""));
        JsonNode videoResponse = withoutCompleted(videoHistory(true, "success", "clip.mp4", true));
        assertThat(ComfyUiHistory.image(imageResponse, promptId, "9"))
                .isEqualTo(new ComfyUiHistory.Ready("image.png"));
        assertThat(ComfyUiHistory.video(videoResponse, promptId, "14"))
                .isEqualTo(new ComfyUiHistory.VideoReady("clip.mp4"));
    }

    @Test
    void terminalExecutionErrorWithoutCompletedFlagIsNotADownloadCandidate() {
        JsonNode response = withoutCompleted(history(false, "error", "image.png", "output", ""));
        assertThat(ComfyUiHistory.image(response, promptId, "9"))
                .isInstanceOf(ComfyUiHistory.Failed.class);
        assertThat(ComfyUiHistory.published(response, promptId,
                new ComfyUiWorkflowDefinition.Output("9", "images"), Task.Kind.IMAGE_GENERATION))
                .isInstanceOf(ComfyUiHistory.PublishedFailed.class);
        assertThat(ComfyUiHistory.video(withoutCompleted(videoHistory(false, "error", "clip.mp4", true)),
                promptId, "14")).isInstanceOf(ComfyUiHistory.VideoFailed.class);
    }

    @Test
    void explicitFalseCompletionRemainsPendingEvenWithSuccessfulStatus() {
        JsonNode response = history(false, "success", "image.png", "output", "");
        assertThat(ComfyUiHistory.image(response, promptId, "9"))
                .isInstanceOf(ComfyUiHistory.Pending.class);
        assertThat(ComfyUiHistory.published(response, promptId,
                new ComfyUiWorkflowDefinition.Output("9", "images"), Task.Kind.IMAGE_GENERATION))
                .isInstanceOf(ComfyUiHistory.PublishedPending.class);
        assertThat(ComfyUiHistory.video(videoHistory(false, "success", "clip.mp4", true),
                promptId, "14")).isInstanceOf(ComfyUiHistory.VideoPending.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "running", "unknown", "SUCCESS", "ERROR"})
    void absentCompletedFlagRequiresTrustedTerminalStatus(String statusCode) {
        JsonNode response = withoutCompleted(history(true, statusCode, "image.png", "output", ""));
        if (statusCode == null) ((ObjectNode) response.path(promptId).path("status")).remove("status_str");
        assertBothMediaReject(response, "History status is malformed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "1", "\"true\"", "[]", "{}"})
    void terminalStatusCannotHideMalformedCompletedFlag(String completedJson) {
        for (String statusCode : java.util.List.of("success", "error")) {
            JsonNode response = history(true, statusCode, "image.png", "output", "");
            ((ObjectNode) response.path(promptId).path("status")).set("completed", mapper.readTree(completedJson));
            assertBothMediaReject(response, "History status is malformed");
        }
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

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", ".", "..", "task.id", "task/id", "task?query", "task#fragment",
            "task%2fother", "task\nid"})
    void bothMediaRequireThePersistedSafePromptId(String unsafePromptId) {
        JsonNode response = mapper.createObjectNode();
        assertThatThrownBy(() -> ComfyUiHistory.image(response, unsafePromptId, "9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exact prompt and template output node required");
        assertThatThrownBy(() -> ComfyUiHistory.video(response, unsafePromptId, "14"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exact prompt and video output node required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"entry\"", "1", "true"})
    void malformedExactPromptEntriesAreProtocolFailures(String entryJson) {
        JsonNode response = mapper.createObjectNode()
                .set(promptId, mapper.readTree(entryJson));
        assertBothMediaReject(response, "History entry is malformed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"running\"", "1", "true", "{}",
            "{\"completed\":null}", "{\"completed\":1}", "{\"completed\":\"true\"}",
            "{\"completed\":[]}", "{\"completed\":{}}"})
    void malformedStatusesAndNonBooleanCompletionAreProtocolFailures(String statusJson) {
        var response = mapper.createObjectNode();
        response.putObject(promptId).set("status", mapper.readTree(statusJson));
        assertBothMediaReject(response, "History status is malformed");
    }

    @Test
    void missingStatusIsAProtocolFailureBeforeReadingOutputs() {
        var response = mapper.createObjectNode();
        response.putObject(promptId);
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
        ((ObjectNode) response.path(promptId).path("outputs").path("14"))
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
        ((ObjectNode) response.path(promptId).path("outputs").path("14")
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
        assertThatThrownBy(() -> ComfyUiHistory.published(response, promptId,
                new ComfyUiWorkflowDefinition.Output("9", "images"), Task.Kind.IMAGE_GENERATION))
                .isInstanceOf(ComfyUiClient.ProtocolFailure.class).hasMessage(message);
    }

    private JsonNode withoutCompleted(JsonNode response) {
        ((ObjectNode) response.path(promptId).path("status")).remove("completed");
        return response;
    }

    private JsonNode videoHistory(boolean complete, String status,
            String filename, boolean animated) {
        var root = mapper.createObjectNode();
        var entry = root.putObject(promptId);
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
        var entry = root.putObject(promptId);
        entry.putObject("status").put("completed", complete).put("status_str", status);
        var image = entry.putObject("outputs").putObject("9").putArray("images")
                .addObject();
        image.put("filename", filename);
        image.put("type", type);
        image.put("subfolder", subfolder);
        return root;
    }
}
