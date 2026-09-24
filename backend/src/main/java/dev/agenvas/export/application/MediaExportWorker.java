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

/** Fenced local export of an immutable video-version snapshot to silent normalized MP4. */
@Component
public class MediaExportWorker {

    private static final long MAX_EXPORT_BYTES = 500L * 1024 * 1024;
    private static final Duration EXPORT_TIMEOUT = Duration.ofMinutes(3);
    private static final long HEARTBEAT_NANOS = Duration.ofSeconds(8).toNanos();
    private final TaskService tasks;
    private final AssetService assets;
    private final LocalAssetStorage storage;
    private final MediaToolRunner mediaTools;
    private final ObjectMapper mapper;

    public MediaExportWorker(TaskService tasks, AssetService assets,
            LocalAssetStorage storage, MediaToolRunner mediaTools, ObjectMapper mapper) {
        this.tasks = tasks;
        this.assets = assets;
        this.storage = storage;
        this.mediaTools = mediaTools;
        this.mapper = mapper;
    }

    /** One bounded claim is executed outside the short claim and completion transactions. */
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

    /** Never accepts paths or filter expressions from Task JSON; all inputs are archived IDs. */
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

    /** Creates scratch and encodes only when this Task lacks an archived MP4. */
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

    /** The returned stream holds scratch until the archive has consumed its bytes. */
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
            // Probing several archived inputs can exceed one lease period; keep the claim fenced.
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
        mediaTools.ffmpegExport(arguments, EXPORT_TIMEOUT,
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

    /** A failed immediate delete remains confined to the janitor's known scratch shape. */
    private void cleanupWorkspace(LocalAssetStorage.ExportWorkspace workspace, Path output) {
        try {
            Files.deleteIfExists(output);
        } catch (IOException ignored) {
            // The janitor removes an old, unlocked scratch directory later.
        } finally {
            workspace.close();
        }
    }

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

    private String seconds(int milliseconds) {
        return BigDecimal.valueOf(milliseconds, 3).toPlainString();
    }

    /** An intermediate encode exceeding the archive cap is stopped before final ingestion. */
    private static final class ExportTooLargeException extends RuntimeException {}
}
