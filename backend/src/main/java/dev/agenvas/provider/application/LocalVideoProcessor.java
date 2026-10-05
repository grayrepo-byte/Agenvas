package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.application.LocalVideoOperationBounds;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.VideoOperation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Bounded local processing of authorized archived video; no arbitrary URLs, commands or filters. */
@Component
public class LocalVideoProcessor {
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final long MAX_SCRATCH_BYTES = 128L * 1024 * 1024;
    private static final long MAX_AUDIO_BYTES = 32L * 1024 * 1024;
    private static final long MAX_SILENT_VIDEO_BYTES = 200L * 1024 * 1024;
    private static final String FRAME_PATTERN = "frame-%04d.png";
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final MediaToolRunner tools;
    private final LocalImageProcessorAdapter depth;
    private final TaskService tasks;
    private final ObjectMapper mapper;

    public LocalVideoProcessor(ArtifactService artifacts, AssetService assets, MediaToolRunner tools,
            LocalImageProcessorAdapter depth, TaskService tasks, ObjectMapper mapper) {
        this.artifacts = artifacts; this.assets = assets; this.tools = tools;
        this.depth = depth; this.tasks = tasks; this.mapper = mapper;
    }

    String preflight(AttemptContext context, VideoOperation operation) {
        try {
            var file = source(context);
            LocalVideoOperationBounds.require(file.asset());
            if (operation == VideoOperation.DEPTH_MAP && !depth.depthModelAvailable()) return "LOCAL_DEPTH_MODEL_UNAVAILABLE";
            if (operation == VideoOperation.EXTRACT_AUDIO && !hasAudio(file.path())) return "VIDEO_AUDIO_TRACK_MISSING";
            return null;
        } catch (RuntimeException unavailable) { return "PROVIDER_UNSUPPORTED_INPUT"; }
    }

    Submission submit(AttemptContext context, VideoOperation operation) {
        Path scratch = null;
        try {
            var source = source(context);
            LocalVideoOperationBounds.require(source.asset());
            scratch = Files.createTempDirectory("agenvas-video-tool-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path output = scratch.resolve(operation == VideoOperation.EXTRACT_AUDIO ? "audio.wav" : "depth.mp4");
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            if (operation == VideoOperation.EXTRACT_AUDIO) {
                if (!hasAudio(source.path())) return new Submission.Rejected("VIDEO_AUDIO_TRACK_MISSING");
                var command = inputArguments(source.path());
                command.addAll(List.of("-map", "0:a:0", "-vn", "-map_metadata", "-1", "-map_chapters", "-1",
                        "-ac", "2", "-ar", "48000", "-c:a", "pcm_s16le", "-fs", Long.toString(MAX_AUDIO_BYTES),
                        "-y", output.toString()));
                run(context, command, scratch, deadline);
            } else {
                int edge = LocalVideoOperationBounds.DEPTH_EDGE;
                var command = inputArguments(source.path());
                command.addAll(List.of("-map", "0:v:0", "-an", "-vf", "fps=" + LocalVideoOperationBounds.DEPTH_FPS
                        + ",scale=" + edge + ":" + edge + ":force_original_aspect_ratio=decrease:force_divisible_by=2",
                        "-frames:v", Integer.toString(LocalVideoOperationBounds.MAX_DURATION_MS / 1000 * LocalVideoOperationBounds.DEPTH_FPS),
                        "-y", scratch.resolve(FRAME_PATTERN).toString()));
                run(context, command, scratch, deadline);
                List<Path> frames;
                try (var paths = Files.list(scratch)) {
                    frames = paths.filter(path -> path.getFileName().toString().startsWith("frame-")).sorted().toList();
                }
                if (frames.isEmpty()) return new Submission.Rejected("LOCAL_VIDEO_PROCESSING_FAILED");
                long scratchBytes = 0;
                for (Path frame : frames) {
                    tick(context);
                    if (System.nanoTime() >= deadline) return new Submission.Rejected("LOCAL_VIDEO_PROCESSING_TIMEOUT");
                    scratchBytes += Files.size(frame);
                    if (scratchBytes > MAX_SCRATCH_BYTES) return new Submission.Rejected("LOCAL_VIDEO_LIMIT_EXCEEDED");
                    var image = ImageIO.read(frame.toFile());
                    if (image == null || !ImageIO.write(depth.depth(image), "png", frame.toFile())) {
                        return new Submission.Rejected("LOCAL_VIDEO_PROCESSING_FAILED");
                    }
                }
                command = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                        "-protocol_whitelist", "file,pipe", "-framerate", Integer.toString(LocalVideoOperationBounds.DEPTH_FPS),
                        "-i", scratch.resolve(FRAME_PATTERN).toString(), "-an", "-map_metadata", "-1", "-threads", "2",
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                        "-movflags", "+faststart", "-fs", Long.toString(MAX_SCRATCH_BYTES), "-y", output.toString()));
                run(context, command, scratch, deadline);
            }
            tick(context);
            UUID owner = context.ownerId();
            var task = context.lease();
            var asset = operation == VideoOperation.EXTRACT_AUDIO
                    ? assets.archiveTaskAudio(owner, task.projectId(), task.id(), () -> open(output))
                    : assets.archiveTaskVideo(owner, task.projectId(), task.id(), () -> open(output));
            var content = MediaResult.content(mapper, task, asset.id(), task.input().path("prompt").asText());
            var parameters = content.withObject("parameters");
            parameters.put("operation", operation.name()).put("local", true);
            if (operation == VideoOperation.DEPTH_MAP) parameters.put("frameRate", LocalVideoOperationBounds.DEPTH_FPS);
            return new Submission.CompletedArtifact(content);
        } catch (ProcessingTimeout timedOut) {
            return new Submission.Rejected("LOCAL_VIDEO_PROCESSING_TIMEOUT");
        } catch (ScratchLimit exceeded) {
            return new Submission.Rejected("LOCAL_VIDEO_LIMIT_EXCEEDED");
        } catch (CancellationException canceled) {
            return new Submission.Rejected("LOCAL_VIDEO_PROCESSING_CANCELED");
        } catch (RuntimeException | IOException failure) {
            return new Submission.Rejected("LOCAL_VIDEO_PROCESSING_FAILED");
        } finally {
            if (scratch != null) {
                try (var paths = Files.walk(scratch)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException failure) {
                    // This private scratch directory is never part of the durable Asset archive.
                    org.slf4j.LoggerFactory.getLogger(LocalVideoProcessor.class).warn("Video tool scratch cleanup failed");
                }
            }
        }
    }

    private AssetService.AssetFile source(AttemptContext context) {
        UUID id = UUID.fromString(context.lease().input().path("videoOperation").path("sourceVersionId").asText());
        var version = artifacts.requireMediaVersionForTask(context.ownerId(), context.lease().projectId(), id, Artifact.Kind.VIDEO);
        return assets.get(context.ownerId(), context.lease().projectId(), UUID.fromString(version.content().path("assetId").asText()));
    }

    /** Copies the pinned source video stream without audio or re-encoding; never calls the media provider. */
    ObjectNode silentVideoResult(AttemptContext context) {
        Path scratch = null;
        try {
            var source = source(context);
            scratch = Files.createTempDirectory("agenvas-silent-video-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path output = scratch.resolve("silent.mp4");
            var command = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-protocol_whitelist", "file,pipe", "-i", source.path().toString(),
                    "-map", "0:v:0", "-an", "-c:v", "copy", "-map_metadata", "-1", "-map_chapters", "-1",
                    "-movflags", "+faststart", "-y", output.toString()));
            run(context, command, scratch, System.nanoTime() + TIMEOUT.toNanos(), MAX_SILENT_VIDEO_BYTES);
            tick(context);
            var task = context.lease();
            // Audio and silent video need separate immutable archive identities within the same Task.
            UUID archiveId = UUID.nameUUIDFromBytes(("agenvas:silent-video:v1:" + task.id())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var asset = assets.archiveTaskVideo(context.ownerId(), task.projectId(), archiveId, () -> open(output));
            ObjectNode content = MediaResult.content(mapper, task, asset.id(), VideoOperation.SILENT_VIDEO_LABEL);
            content.withObject("parameters").put("operation", VideoOperation.EXTRACT_AUDIO.name())
                    .put("videoOperationOutput", VideoOperation.SILENT_VIDEO_RESULT).put("local", true);
            return content;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot separate silent video", failure);
        } finally {
            if (scratch != null) {
                try (var paths = Files.walk(scratch)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException failure) {
                    org.slf4j.LoggerFactory.getLogger(LocalVideoProcessor.class).warn("Silent video scratch cleanup failed");
                }
            }
        }
    }

    private boolean hasAudio(Path file) {
        return !mapper.readTree(tools.ffprobe(List.of("-v", "error", "-protocol_whitelist", "file,pipe",
                "-select_streams", "a:0", "-show_entries", "stream=index", "-of", "json", file.toString())))
                .path("streams").isEmpty();
    }

    private List<String> inputArguments(Path file) {
        return new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-protocol_whitelist", "file,pipe",
                "-threads", "2", "-filter_threads", "2", "-i", file.toString(), "-t",
                Integer.toString(LocalVideoOperationBounds.MAX_DURATION_MS / 1000)));
    }

    private void run(AttemptContext context, List<String> arguments, Path scratch, long deadline) {
        run(context, arguments, scratch, deadline, MAX_SCRATCH_BYTES);
    }

    private void run(AttemptContext context, List<String> arguments, Path scratch, long deadline, long maxBytes) {
        Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
        if (remaining.isNegative() || remaining.isZero()) throw new ProcessingTimeout();
        try {
            tools.ffmpegExport(arguments, scratch, remaining,
                    () -> tasks.get(context.ownerId(), context.lease().projectId(), context.lease().id()).cancelRequested(),
                    () -> { tick(context); checkScratch(scratch, maxBytes); });
            checkScratch(scratch, maxBytes);
        } catch (RuntimeException failure) {
            if (System.nanoTime() >= deadline) throw new ProcessingTimeout();
            throw failure;
        }
    }

    private static final class ProcessingTimeout extends RuntimeException {}
    private static final class ScratchLimit extends RuntimeException {}

    private void checkScratch(Path directory, long maxBytes) {
        try (var files = Files.list(directory)) {
            long bytes = 0;
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                bytes += Files.size(file);
                if (bytes > maxBytes) throw new ScratchLimit();
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot inspect video processing scratch", failure);
        }
    }

    private void tick(AttemptContext context) {
        var task = context.lease();
        if (tasks.get(context.ownerId(), task.projectId(), task.id()).cancelRequested()) throw new CancellationException();
        tasks.heartbeat(task.id(), task.leaseOwner(), task.leaseEpoch());
    }

    private java.io.InputStream open(Path path) {
        try { return Files.newInputStream(path); }
        catch (IOException failure) { throw new IllegalStateException("Cannot read video operation output", failure); }
    }
}
