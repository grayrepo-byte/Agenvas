package dev.agenvas.provider.infrastructure;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Interprets one exact ComfyUI prompt history without treating absent history as failure. */
public final class ComfyUiHistory {

    private ComfyUiHistory() {}

    /** The trusted output node is fixed by the installed template, never chosen by a user. */
    public static ImageResult image(JsonNode response, UUID promptId, String outputNodeId) {
        if (response == null || !response.isObject() || promptId == null
                || outputNodeId == null || !outputNodeId.matches("[0-9]{1,8}")) {
            throw new IllegalArgumentException("Exact prompt and template output node required");
        }
        JsonNode entry = response.path(promptId.toString());
        if (entry.isMissingNode()) {
            if (response.isEmpty()) return new Pending();
            throw new ComfyUiClient.ProtocolFailure("History returned an unrelated prompt");
        }
        if (!entry.isObject()) {
            throw new ComfyUiClient.ProtocolFailure("History entry is malformed");
        }
        JsonNode status = entry.path("status");
        if (!status.isObject() || !status.path("completed").isBoolean()) {
            throw new ComfyUiClient.ProtocolFailure("History status is malformed");
        }
        // ComfyUI records runtime failures with completed=false and status_str=error.
        if ("error".equals(status.path("status_str").asText())) return new Failed();
        if (!status.path("completed").booleanValue()) return new Pending();
        if (!"success".equals(status.path("status_str").asText())) {
            throw new ComfyUiClient.ProtocolFailure("Completed history has unknown status");
        }
        JsonNode outputs = entry.path("outputs");
        JsonNode images = outputs.path(outputNodeId).path("images");
        if (!images.isArray() || images.size() != 1) {
            throw new ComfyUiClient.ProtocolFailure("Fixed image output is missing or ambiguous");
        }
        JsonNode image = images.get(0);
        String filename = image.path("filename").asText("");
        if (!"output".equals(image.path("type").asText())
                || !image.path("subfolder").asText("").isEmpty()
                || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                || filename.contains("..")) {
            throw new ComfyUiClient.ProtocolFailure("Fixed image output path is unsafe");
        }
        return new Ready(filename);
    }

    /** Native SaveVideo reports one animated MP4 in its historical `images` UI bucket. */
    public static VideoResult video(JsonNode response, UUID promptId, String outputNodeId) {
        if (response == null || !response.isObject() || promptId == null
                || outputNodeId == null || !outputNodeId.matches("[0-9]{1,8}")) {
            throw new IllegalArgumentException("Exact prompt and video output node required");
        }
        JsonNode entry = response.path(promptId.toString());
        if (entry.isMissingNode()) {
            if (response.isEmpty()) return new VideoPending();
            throw new ComfyUiClient.ProtocolFailure("History returned an unrelated prompt");
        }
        if (!entry.isObject()) {
            throw new ComfyUiClient.ProtocolFailure("History entry is malformed");
        }
        JsonNode status = entry.path("status");
        if (!status.isObject() || !status.path("completed").isBoolean()) {
            throw new ComfyUiClient.ProtocolFailure("History status is malformed");
        }
        if ("error".equals(status.path("status_str").asText())) return new VideoFailed();
        if (!status.path("completed").booleanValue()) return new VideoPending();
        if (!"success".equals(status.path("status_str").asText())) {
            throw new ComfyUiClient.ProtocolFailure("Completed history has unknown status");
        }
        JsonNode output = entry.path("outputs").path(outputNodeId);
        JsonNode files = output.path("images");
        JsonNode animated = output.path("animated");
        if (!files.isArray() || files.size() != 1 || !animated.isArray()
                || animated.size() != 1 || !animated.get(0).isBoolean()
                || !animated.get(0).booleanValue()) {
            throw new ComfyUiClient.ProtocolFailure("Fixed video output is missing or ambiguous");
        }
        JsonNode file = files.get(0);
        String filename = file.path("filename").asText("");
        if (!"output".equals(file.path("type").asText())
                || !file.path("subfolder").asText("").isEmpty()
                || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.mp4")
                || filename.contains("..")) {
            throw new ComfyUiClient.ProtocolFailure("Fixed MP4 output path is unsafe");
        }
        return new VideoReady(filename);
    }

    /** The history record may not exist until the original prompt finishes. */
    public sealed interface ImageResult permits Pending, Failed, Ready {}

    /** No completed history yet; query the same prompt id later. */
    public record Pending() implements ImageResult {}

    /** ComfyUI recorded terminal execution failure for this prompt. */
    public record Failed() implements ImageResult {}

    /** One safe output file from the template's expected image node. */
    public record Ready(String filename) implements ImageResult {}

    /** Video polling is deliberately distinct from the image-only output parser. */
    public sealed interface VideoResult permits VideoPending, VideoFailed, VideoReady {}

    public record VideoPending() implements VideoResult {}

    public record VideoFailed() implements VideoResult {}

    public record VideoReady(String filename) implements VideoResult {}
}
