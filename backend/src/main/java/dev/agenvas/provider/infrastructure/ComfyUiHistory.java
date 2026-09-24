package dev.agenvas.provider.infrastructure;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 解释指定 ComfyUI prompt 的固定模板输出；尚无历史时保留待查询状态，不误判失败。 */
public final class ComfyUiHistory {

    /** 纯解析器不允许实例化。 */
    private ComfyUiHistory() {}

    /** 可信输出节点由已安装模板固定，用户不能自行指定。 */
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

    /** 解析固定视频输出节点，并校验 SaveVideo 的 MP4 文件名和动画标记。
     * @param response ComfyUI history 查询响应
     * @param promptId 已持久化的原始 prompt ID
     * @param outputNodeId 固定视频模板的输出节点 ID
     * @return 尚未完成、失败或包含一个安全 MP4 文件名的结果
     */
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

    /** 原 prompt 完成前，ComfyUI 可能尚未创建对应历史记录。 */
    public sealed interface ImageResult permits Pending, Failed, Ready {}

    /** 尚无完成历史；稍后继续查询同一个 prompt ID。 */
    public record Pending() implements ImageResult {}

    /** ComfyUI 已将该 prompt 记录为终态失败。 */
    public record Failed() implements ImageResult {}

    /** 图片模板预期输出节点的唯一安全文件。
     * @param filename ComfyUI 输出目录中的文件名，不含子目录
     */
    public record Ready(String filename) implements ImageResult {}

    /** 视频轮询使用独立结果类型，避免误用仅识别图片的解析逻辑。 */
    public sealed interface VideoResult permits VideoPending, VideoFailed, VideoReady {}

    /** 原 prompt 尚无已完成视频历史；继续查询同一个 prompt ID。
     */
    public record VideoPending() implements VideoResult {}

    /** ComfyUI 已将原 prompt 标记为终态执行失败。
     */
    public record VideoFailed() implements VideoResult {}

    /** 视频模板预期输出节点的唯一安全 MP4。
     * @param filename ComfyUI 输出目录中的 MP4 文件名
     */
    public record VideoReady(String filename) implements VideoResult {}
}
