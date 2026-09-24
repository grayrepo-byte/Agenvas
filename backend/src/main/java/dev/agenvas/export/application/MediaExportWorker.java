package dev.agenvas.export.application;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 使用 fencing 租约将固定视频版本快照本地合成为无声、统一画幅的 MP4。 */
@Component
public class MediaExportWorker {

    /** 归档前允许的最大 MP4 字节数，编码期间超限即停止。 */
    private static final long MAX_EXPORT_BYTES = 500L * 1024 * 1024;
    /** 单次 FFmpeg 编码的最长运行时间。 */
    private static final Duration EXPORT_TIMEOUT = Duration.ofMinutes(3);
    /** 编码期间检查任务取消和输出体积的租约续期间隔。 */
    private static final long HEARTBEAT_NANOS = Duration.ofSeconds(8).toNanos();
    /** 认领任务、续租、检查取消并以 fencing 条件提交终态。 */
    private final TaskService tasks;
    /** 读取固定视频素材并按任务身份幂等归档成品。 */
    private final AssetService assets;
    /** 创建受控 scratch 目录并在流关闭后释放文件锁。 */
    private final LocalAssetStorage storage;
    /** 通过固定二进制和参数数组执行 FFmpeg 与 ffprobe。 */
    private final MediaToolRunner mediaTools;
    /** 序列化归档结果 JSON。 */
    private final ObjectMapper mapper;

    /** 注入本地导出所需服务；耗时编码在数据库认领事务结束后运行。 */
    public MediaExportWorker(TaskService tasks, AssetService assets,
            LocalAssetStorage storage, MediaToolRunner mediaTools, ObjectMapper mapper) {
        this.tasks = tasks;
        this.assets = assets;
        this.storage = storage;
        this.mediaTools = mediaTools;
        this.mapper = mapper;
    }

    /** 每轮只短事务认领一个导出任务，完成编码后再通过单独事务提交结果。 */
    public int runOnce(String workerId) {
        List<Task> claimed = tasks.claimExportsDue(workerId, 1);
        for (Task lease : claimed) {
            try {
                tasks.succeed(lease, workerId, render(lease, workerId));
            } catch (CancellationException exception) {
                tasks.fail(lease, workerId, "EXPORT_CANCELED");
            } catch (ExportTooLargeException exception) {
                tasks.fail(lease, workerId, "EXPORT_TOO_LARGE");
            } catch (MediaToolRunner.MediaToolException exception) {
                tasks.fail(lease, workerId,
                        exception.invalidInput() ? "EXPORT_INVALID_MEDIA" : "EXPORT_TOOL_FAILED");
            } catch (RuntimeException exception) {
                tasks.fail(lease, workerId, "EXPORT_FAILED");
            }
        }
        return claimed.size();
    }

    /** 不接受任务 JSON 中的路径或滤镜表达式，只解析固定素材 ID 和受限区间。 */
    private ObjectNode render(Task lease, String workerId) {
        UUID ownerId = tasks.ownerForWorker(lease);
        JsonNode snapshot = lease.input();
        JsonNode segments = snapshot.path("segments");
        if (!segments.isArray() || segments.isEmpty() || segments.size() > 6) {
            throw new IllegalStateException("Export snapshot has invalid segments");
        }
        int[] dimensions = switch (snapshot.path("aspectRatio").asText()) {
            case "LANDSCAPE_16_9" -> new int[] {1280, 720};
            case "PORTRAIT_9_16" -> new int[] {720, 1280};
            case "SQUARE_1_1" -> new int[] {720, 720};
            default -> throw new IllegalStateException("Export snapshot has invalid aspect ratio");
        };
        Asset archived = assets.archiveTaskVideo(ownerId, lease.projectId(), lease.id(),
                () -> encode(lease, workerId, ownerId, segments, dimensions));
        if (tasks.exportShouldStop(lease)) {
            throw new CancellationException("Export canceled after archive recovery");
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("assetId", archived.id().toString());
        result.put("contentType", archived.contentType());
        result.put("byteSize", archived.byteSize());
        return result;
    }

    /** 创建临时工作目录；只有幂等归档中不存在该任务成品时才执行编码。 */
    private InputStream encode(Task lease, String workerId, UUID ownerId, JsonNode segments,
            int[] dimensions) {
        var workspace = storage.createExportWorkDirectory(lease.projectId());
        Path output = workspace.directory().resolve("silent-export.mp4");
        try {
            return encodeInWorkspace(lease, workerId, ownerId, segments, dimensions,
                    output, workspace);
        } catch (RuntimeException failure) {
            cleanupWorkspace(workspace, output);
            throw failure;
        }
    }

    /** 返回流持有 scratch 目录锁，直到归档器读完数据并关闭该流。 */
    private InputStream encodeInWorkspace(Task lease, String workerId, UUID ownerId,
            JsonNode segments, int[] dimensions, Path output,
            LocalAssetStorage.ExportWorkspace workspace) {
        List<String> arguments = new ArrayList<>(List.of(
                "-hide_banner", "-loglevel", "error", "-nostdin"));
        StringBuilder filter = new StringBuilder();
        StringBuilder inputs = new StringBuilder();
        int index = 0;
        for (JsonNode segment : segments) {
            if (tasks.exportShouldStop(lease)) {
                throw new CancellationException("Export canceled before media read");
            }
            UUID assetId = UUID.fromString(segment.path("assetId").asText());
            AssetService.AssetFile source = assets.get(ownerId, lease.projectId(), assetId);
            if (source.asset().mediaKind() != Asset.MediaKind.VIDEO
                    || !source.asset().sha256().equals(segment.path("assetSha256").asText())) {
                throw new IllegalStateException("Pinned export asset changed");
            }
            int startMs = segment.path("startMs").asInt(-1);
            int endMs = segment.path("endMs").asInt(-1);
            if (startMs < 0 || endMs <= startMs || endMs > 60_000) {
                throw new IllegalStateException("Pinned export interval is invalid");
            }
            verifyDuration(source.path(), endMs);
            // 多个素材的探测可能超过租约时长，因此每段素材校验后续租。
            tasks.heartbeat(lease.id(), workerId, lease.leaseEpoch());
            arguments.add("-i");
            arguments.add(source.path().toString());
            if (index > 0) filter.append(';');
            filter.append('[').append(index).append(":v]trim=start=")
                    .append(seconds(startMs)).append(":end=").append(seconds(endMs))
                    .append(",setpts=PTS-STARTPTS,fps=24,scale=")
                    .append(dimensions[0]).append(':').append(dimensions[1])
                    .append(":force_original_aspect_ratio=decrease,pad=")
                    .append(dimensions[0]).append(':').append(dimensions[1])
                    .append(":(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p[v")
                    .append(index).append(']');
            inputs.append("[v").append(index).append(']');
            index++;
        }
        filter.append(';').append(inputs).append("concat=n=")
                .append(index).append(":v=1:a=0[outv]");
        arguments.addAll(List.of("-filter_complex", filter.toString(), "-map", "[outv]",
                "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-threads", "2", "-movflags", "+faststart", "-y", output.toString()));
        long[] lastHeartbeat = {System.nanoTime()};
        mediaTools.ffmpegExport(arguments, workspace.directory(), EXPORT_TIMEOUT,
                () -> tasks.exportShouldStop(lease), () -> {
                    if (System.nanoTime() - lastHeartbeat[0] >= HEARTBEAT_NANOS) {
                        tasks.heartbeat(lease.id(), workerId, lease.leaseEpoch());
                        lastHeartbeat[0] = System.nanoTime();
                    }
                    try {
                        if (Files.exists(output) && Files.size(output) > MAX_EXPORT_BYTES) {
                            throw new ExportTooLargeException();
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException("Cannot inspect export output", exception);
                    }
                });
        if (tasks.exportShouldStop(lease)) {
            throw new CancellationException("Export canceled after encoding");
        }
        try {
            return new FilterInputStream(Files.newInputStream(output)) {
                private boolean closed;

                @Override
                public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try {
                        super.close();
                    } finally {
                        cleanupWorkspace(workspace, output);
                    }
                }
            };
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read export output", exception);
        }
    }

    /** 即时删除失败时仍由临时目录清理器按受限目录结构回收。 */
    private void cleanupWorkspace(LocalAssetStorage.ExportWorkspace workspace, Path output) {
        try {
            Files.deleteIfExists(output);
        } catch (IOException ignored) {
            // The janitor removes an old, unlocked scratch directory later.
        } finally {
            workspace.close();
        }
    }

    /** 使用 ffprobe 确认归档输入确为视频，且请求裁剪终点没有超出媒体时长。 */
    private void verifyDuration(Path archivedInput, int requestedEndMs) {
        JsonNode probe = mapper.readTree(mediaTools.ffprobe(List.of("-v", "error",
                "-select_streams", "v:0", "-show_entries",
                "stream=codec_type:format=duration", "-of", "json",
                archivedInput.toString())));
        double durationSeconds = probe.path("format").path("duration").asDouble();
        if (!"video".equals(probe.path("streams").path(0).path("codec_type").asText())
                || !Double.isFinite(durationSeconds)
                || requestedEndMs > durationSeconds * 1000 + 100) {
            throw new IllegalStateException("Export interval exceeds archived video");
        }
    }

    /** 将整数毫秒格式化为 FFmpeg 可解析的十进制秒数。 */
    private String seconds(int milliseconds) {
        return BigDecimal.valueOf(milliseconds, 3).toPlainString();
    }

    /** 标记编码文件超过归档上限；由 Worker 映射为明确的任务失败码。 */
    private static final class ExportTooLargeException extends RuntimeException {}
}
