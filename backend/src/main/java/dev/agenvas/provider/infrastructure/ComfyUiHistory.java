package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.ComfyUiWorkflowDefinition;
import dev.agenvas.task.domain.Task;
import tools.jackson.databind.JsonNode;

/** 解释指定 ComfyUI prompt 的发布输出或历史模板输出；尚无历史时保持待查询，不误判失败。 */
public final class ComfyUiHistory {

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_ERROR = "error";

    /** 纯解析器不允许实例化。 */
    private ComfyUiHistory() {}

    public sealed interface PublishedResult permits PublishedPending, PublishedFailed, PublishedReady {}
    public record PublishedPending() implements PublishedResult {}
    public record PublishedFailed() implements PublishedResult {}
    public record PublishedReady(String filename, String subfolder, String contentType) implements PublishedResult {}

    /** Select the first matching safe file from the published node and field, in provider order. */
    public static PublishedResult published(JsonNode response, String promptId,
            ComfyUiWorkflowDefinition.Output output, Task.Kind kind) {
        HistoryState state = historyState(response, promptId, output.nodeId(), "published");
        if (state == HistoryState.PENDING) return new PublishedPending();
        if (state == HistoryState.FAILED) return new PublishedFailed();
        JsonNode files = response.path(promptId).path("outputs").path(output.nodeId()).path(output.field());
        if (!files.isArray() || files.isEmpty()) throw new ComfyUiClient.ProtocolFailure("Published output is missing");
        for (JsonNode file : files) {
            String filename = file.path("filename").asText("");
            String subfolder = file.path("subfolder").asText("");
            if (!"output".equals(file.path("type").asText()) || !safeFile(filename) || !safeSubfolder(subfolder)) continue;
            String contentType;
            if (kind == Task.Kind.VIDEO_GENERATION && filename.endsWith(".mp4")) contentType = "video/mp4";
            else if (kind == Task.Kind.IMAGE_GENERATION && filename.endsWith(".png")) contentType = "image/png";
            else if (kind == Task.Kind.IMAGE_GENERATION && (filename.endsWith(".jpg") || filename.endsWith(".jpeg"))) contentType = "image/jpeg";
            else if (kind == Task.Kind.IMAGE_GENERATION && filename.endsWith(".webp")) contentType = "image/webp";
            else continue;
            return new PublishedReady(filename, subfolder, contentType);
        }
        throw new ComfyUiClient.ProtocolFailure("Published output has no safe matching media");
    }

    static boolean safeFile(String value) {
        return value != null && value.matches("[\\p{L}\\p{N}_][\\p{L}\\p{N} _.-]{0,159}") && !value.contains("..");
    }

    static boolean safeSubfolder(String value) {
        if (value == null || value.length() > 500) return false;
        if (value.isEmpty()) return true;
        for (String segment : value.split("/", -1)) if (!safeFile(segment)) return false;
        return true;
    }

    /** 可信输出节点由已安装模板固定，用户不能自行指定。 */
    public static ImageResult image(JsonNode response, String promptId, String outputNodeId) {
        HistoryState state = historyState(response, promptId, outputNodeId, "template");
        if (state == HistoryState.PENDING) return new Pending();
        if (state == HistoryState.FAILED) return new Failed();
        JsonNode images = response.path(promptId).path("outputs")
                .path(outputNodeId).path("images");
        if (!images.isArray() || images.isEmpty()) {
            throw new ComfyUiClient.ProtocolFailure("Fixed image output is missing");
        }
        for (JsonNode image : images) {
            String filename = image.path("filename").asText("");
            if (!"output".equals(image.path("type").asText())
                    || !image.path("subfolder").asText("").isEmpty()
                    || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                    || filename.contains("..")
                    || (filename.contains(".") && !filename.matches("(?i).*\\.(png|jpe?g|webp)"))) continue;
            // Legacy template filenames may be extensionless; archived bytes still undergo image decoding.
            return new Ready(filename);
        }
        throw new ComfyUiClient.ProtocolFailure("Fixed image output path is unsafe");
    }

    /** 解析固定视频输出节点，并校验 SaveVideo 的 MP4 文件名和动画标记。
     * @param response ComfyUI history 查询响应
     * @param promptId 已持久化的原始 prompt ID
     * @param outputNodeId 固定视频模板的输出节点 ID
     * @return 尚未完成、失败或包含一个安全 MP4 文件名的结果
     */
    public static VideoResult video(JsonNode response, String promptId, String outputNodeId) {
        HistoryState state = historyState(response, promptId, outputNodeId, "video");
        if (state == HistoryState.PENDING) return new VideoPending();
        if (state == HistoryState.FAILED) return new VideoFailed();
        JsonNode output = response.path(promptId).path("outputs").path(outputNodeId);
        JsonNode files = output.path("images");
        JsonNode animated = output.path("animated");
        if (!files.isArray() || files.isEmpty() || !animated.isArray()
                || (animated.size() != 1 && animated.size() != files.size())) {
            throw new ComfyUiClient.ProtocolFailure("Fixed video output is missing or ambiguous");
        }
        boolean hasAnimatedFile = false;
        for (int index = 0; index < files.size(); index++) {
            // SaveVideo can use one animation flag for its whole batch, or flags aligned with files.
            JsonNode flag = animated.get(animated.size() == 1 ? 0 : index);
            if (!flag.isBoolean() || !flag.booleanValue()) continue;
            hasAnimatedFile = true;
            JsonNode file = files.get(index);
            String filename = file.path("filename").asText("");
            if (!"output".equals(file.path("type").asText())
                    || !file.path("subfolder").asText("").isEmpty()
                    || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.mp4")
                    || filename.contains("..")) continue;
            return new VideoReady(filename);
        }
        throw new ComfyUiClient.ProtocolFailure(hasAnimatedFile
                ? "Fixed MP4 output path is unsafe" : "Fixed video output is missing or ambiguous");
    }

    private enum HistoryState { PENDING, FAILED, READY }

    private static HistoryState historyState(JsonNode response, String promptId,
            String outputNodeId, String outputKind) {
        if (response == null || !response.isObject() || !ComfyUiClient.safePromptId(promptId)
                || outputNodeId == null || !outputNodeId.matches("[0-9]{1,8}")) {
            throw new IllegalArgumentException("Exact prompt and " + outputKind + " output node required");
        }
        JsonNode entry = response.path(promptId);
        if (entry.isMissingNode()) {
            if (response.isEmpty()) return HistoryState.PENDING;
            throw new ComfyUiClient.ProtocolFailure("History returned an unrelated prompt");
        }
        if (!entry.isObject()) {
            throw new ComfyUiClient.ProtocolFailure("History entry is malformed");
        }
        JsonNode status = entry.path("status");
        if (!status.isObject()) {
            throw new ComfyUiClient.ProtocolFailure("History status is malformed");
        }
        JsonNode completed = status.path("completed");
        String statusCode = status.path("status_str").asText();
        // Native proxies may omit completed only for terminal status; provided flags remain strictly boolean.
        if (completed.isMissingNode()
                ? !STATUS_SUCCESS.equals(statusCode) && !STATUS_ERROR.equals(statusCode)
                : !completed.isBoolean()) {
            throw new ComfyUiClient.ProtocolFailure("History status is malformed");
        }
        // ComfyUI records runtime failures with completed=false and status_str=error.
        if (STATUS_ERROR.equals(statusCode)) return HistoryState.FAILED;
        if (completed.isBoolean() && !completed.booleanValue()) return HistoryState.PENDING;
        if (!STATUS_SUCCESS.equals(statusCode)) {
            throw new ComfyUiClient.ProtocolFailure("Completed history has unknown status");
        }
        return HistoryState.READY;
    }

    /** 原 prompt 完成前，ComfyUI 可能尚未创建对应历史记录。 */
    public sealed interface ImageResult permits Pending, Failed, Ready {}

    /** 尚无完成历史；稍后继续查询同一个 prompt ID。 */
    public record Pending() implements ImageResult {}

    /** ComfyUI 已将该 prompt 记录为终态失败。 */
    public record Failed() implements ImageResult {}

    /** 图片模板预期输出节点中按顺序选中的第一个安全文件。
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

    /** 视频模板预期输出节点中按顺序选中的第一个安全 MP4。
     * @param filename ComfyUI 输出目录中的 MP4 文件名
     */
    public record VideoReady(String filename) implements VideoResult {}
}
