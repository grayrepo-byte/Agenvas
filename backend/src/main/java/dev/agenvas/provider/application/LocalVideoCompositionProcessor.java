package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.application.TaskService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Resolves frozen project inputs, renders outside transactions, and archives one immutable result. */
@Component
public class LocalVideoCompositionProcessor {
    private static final long MAX_SOURCE_BYTES = 1024L * 1024 * 1024;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final LocalVideoCompositionRenderer renderer;
    private final TaskService tasks;
    private final ObjectMapper mapper;

    public LocalVideoCompositionProcessor(ArtifactService artifacts, AssetService assets,
            LocalVideoCompositionRenderer renderer, TaskService tasks, ObjectMapper mapper) {
        this.artifacts = artifacts; this.assets = assets; this.renderer = renderer;
        this.tasks = tasks; this.mapper = mapper;
    }

    String preflight(AttemptContext context) {
        try { resolve(context); return null; }
        catch (RuntimeException invalid) { return "VIDEO_COMPOSITION_INVALID"; }
    }

    Submission submit(AttemptContext context) {
        Path scratch = null;
        try {
            var sources = resolve(context);
            scratch = Files.createTempDirectory("agenvas-video-composition-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var rendered = renderer.render(context.lease().input().path("videoComposition"), sources, scratch,
                    () -> tasks.get(context.ownerId(), context.lease().projectId(), context.lease().id()).cancelRequested(),
                    () -> tasks.heartbeat(context.lease().id(), context.lease().leaseOwner(), context.lease().leaseEpoch()));
            if (tasks.get(context.ownerId(), context.lease().projectId(), context.lease().id()).cancelRequested())
                throw new CancellationException();
            var asset = assets.archiveTaskVideo(context.ownerId(), context.lease().projectId(), context.lease().id(), () -> {
                try { return Files.newInputStream(rendered.path()); }
                catch (IOException failure) { throw new IllegalStateException("Cannot read composition output", failure); }
            });
            var content = MediaResult.content(mapper, context.lease(), asset.id(),
                    context.lease().input().path("videoComposition").path("title").asText());
            content.withObject("parameters").put("local", true).put("operation", "VIDEO_COMPOSITION")
                    .set("videoComposition", rendered.schedule());
            return new Submission.CompletedArtifact(content);
        } catch (VideoCompositionPlanner.DoesNotFit overflow) {
            return new Submission.Rejected("VIDEO_COMPOSITION_AUDIO_OVERFLOW");
        } catch (CancellationException canceled) {
            return new Submission.Rejected("LOCAL_VIDEO_COMPOSITION_CANCELED");
        } catch (MediaToolRunner.MediaToolException failure) {
            return new Submission.Rejected(failure.getMessage().contains("timed out")
                    ? "LOCAL_VIDEO_COMPOSITION_TIMEOUT" : "LOCAL_VIDEO_COMPOSITION_FAILED");
        } catch (IllegalArgumentException invalid) {
            return new Submission.Rejected("VIDEO_COMPOSITION_INVALID");
        } catch (IOException | RuntimeException failure) {
            return new Submission.Rejected("LOCAL_VIDEO_COMPOSITION_FAILED");
        } finally {
            if (scratch != null) {
                try (var paths = Files.walk(scratch)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException failure) {
                    org.slf4j.LoggerFactory.getLogger(LocalVideoCompositionProcessor.class).warn("Video composition scratch cleanup failed");
                }
            }
        }
    }

    private Map<UUID, LocalVideoCompositionRenderer.Source> resolve(AttemptContext context) {
        JsonNode composition = context.lease().input().path("videoComposition");
        if (!composition.isObject() || !composition.path("shots").isArray() || composition.path("shots").isEmpty()
                || composition.path("shots").size() > 12 || !Set.of("MUTE", "BACKGROUND", "KEEP").contains(composition.path("originalAudioMode").asText()))
            throw new IllegalArgumentException("Invalid frozen composition");
        Map<UUID, Artifact.Kind> required = new HashMap<>();
        int lineCount = 0;
        long totalFrames = 0;
        for (JsonNode shot : composition.path("shots")) {
            UUID video = UUID.fromString(shot.path("videoVersionId").asText());
            requireKind(required, video, Artifact.Kind.VIDEO);
            int duration = integer(shot, "durationMs", 1, 600_000);
            integer(shot, "sourceStartMs", 0, 599_999);
            double speed = shot.path("speed").asDouble(1);
            if (!Double.isFinite(speed) || speed < 0.5 || speed > 2) throw new IllegalArgumentException("Invalid shot speed");
            int frames = (int) Math.floor(duration * VideoCompositionPlanner.FPS / (1000 * speed));
            if (frames < 1) throw new IllegalArgumentException("Invalid output shot length");
            int outputDuration = frames * 1000 / VideoCompositionPlanner.FPS;
            int fade = shot.has("fadeMs") ? integer(shot, "fadeMs", 0, 500) : 0;
            if (fade * 2 > outputDuration) throw new IllegalArgumentException("Invalid shot fade");
            totalFrames += frames;
            if (!shot.path("lines").isArray()) throw new IllegalArgumentException("Invalid frozen dialogue list");
            for (JsonNode line : shot.path("lines")) {
                lineCount++;
                requireKind(required, UUID.fromString(line.path("audioVersionId").asText()), Artifact.Kind.AUDIO);
                if (line.hasNonNull("startMs")) integer(line, "startMs", 0, outputDuration - 1);
            }
        }
        if (lineCount > 30 || totalFrames > 60 * VideoCompositionPlanner.FPS) throw new IllegalArgumentException("Composition bounds exceeded");
        JsonNode music = composition.path("music");
        if (music.isObject()) requireKind(required, UUID.fromString(music.path("audioVersionId").asText()), Artifact.Kind.AUDIO);
        Map<UUID, LocalVideoCompositionRenderer.Source> resolved = new HashMap<>();
        Set<UUID> assetIds = new HashSet<>();
        long bytes = 0;
        for (var entry : required.entrySet()) {
            UUID id = entry.getKey();
            var version = artifacts.requireMediaVersionForTask(context.ownerId(), context.lease().projectId(), id, entry.getValue());
            String group = entry.getValue() == Artifact.Kind.VIDEO ? "videos" : "audios";
            long matching = context.lease().input().path("mediaInput").path(group).valueStream().filter(reference ->
                    id.toString().equals(reference.path("versionId").asText())
                            && version.artifactId().toString().equals(reference.path("artifactId").asText())).count();
            if (matching != 1) throw new IllegalArgumentException("Composition input was not pinned");
            var file = assets.get(context.ownerId(), context.lease().projectId(), UUID.fromString(version.content().path("assetId").asText()));
            Asset asset = file.asset();
            if (!asset.mediaKind().name().equals(entry.getValue().name()) || asset.durationMs() == null
                    || asset.durationMs() < 1 || asset.durationMs() > 600_000 || asset.byteSize() < 1)
                throw new IllegalArgumentException("Invalid composition source");
            if (assetIds.add(asset.id())) bytes = Math.addExact(bytes, asset.byteSize());
            if (bytes > MAX_SOURCE_BYTES) throw new IllegalArgumentException("Composition source byte bound exceeded");
            resolved.put(id, new LocalVideoCompositionRenderer.Source(file.path(), asset.durationMs()));
        }
        for (JsonNode shot : composition.path("shots")) {
            var source = resolved.get(UUID.fromString(shot.path("videoVersionId").asText()));
            if ((long) shot.path("sourceStartMs").asInt() + shot.path("durationMs").asInt() > source.durationMs())
                throw new IllegalArgumentException("Composition source range exceeded");
        }
        if (music.isObject()) {
            var source = resolved.get(UUID.fromString(music.path("audioVersionId").asText()));
            int start = music.has("sourceStartMs") ? integer(music, "sourceStartMs", 0, 599_999) : 0;
            double volume = music.path("volume").asDouble(0.2);
            if (start >= source.durationMs() || !Double.isFinite(volume) || volume < 0 || volume > 1)
                throw new IllegalArgumentException("Invalid music input");
        }
        return Map.copyOf(resolved);
    }
    private static void requireKind(Map<UUID, Artifact.Kind> required, UUID id, Artifact.Kind kind) {
        Artifact.Kind previous = required.putIfAbsent(id, kind);
        if (previous != null && previous != kind) throw new IllegalArgumentException("Composition source kind mismatch");
    }
    private static int integer(JsonNode object, String field, int minimum, int maximum) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum || value.intValue() > maximum)
            throw new IllegalArgumentException("Invalid composition timing");
        return value.intValue();
    }
}
