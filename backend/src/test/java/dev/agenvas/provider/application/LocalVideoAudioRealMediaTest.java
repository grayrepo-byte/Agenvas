package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.application.MediaToolsProperties;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.VideoOperation;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Real synthetic FFmpeg media exercises long extraction, track selection and immutable archives. */
class LocalVideoAudioRealMediaTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MediaToolRunner tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final TaskService tasks = mock(TaskService.class);

    @Test
    void extractsTheSelectedTrackForTenMinutesAndKeepsTheWholeSilentVideo() throws Exception {
        Path source = source(600);
        var storage = new LocalAssetStorage(new AssetProperties(root.resolve("archive")), tools, mapper);
        var context = context(source, 600_000, "MP3", 1);
        var processor = processor();
        Asset[] audioOutput = new Asset[1];
        Path[] silentOutput = new Path[1];
        when(assets.archiveTaskAudio(any(), any(), any(), any())).thenAnswer(call -> {
            Supplier<InputStream> output = call.getArgument(3);
            try (var input = output.get()) {
                var archived = storage.storeAudio(context.lease().projectId(), UUID.randomUUID(), input);
                audioOutput[0] = new Asset(UUID.randomUUID(), context.lease().projectId(), Asset.MediaKind.AUDIO,
                        archived.objectKey(), archived.contentType(), archived.byteSize(), archived.sha256(),
                        null, null, archived.durationMs(), null, null, null, Instant.now());
                return audioOutput[0];
            }
        });
        when(assets.archiveTaskVideo(any(), any(), any(), any())).thenAnswer(call -> {
            Supplier<InputStream> output = call.getArgument(3);
            try (var input = output.get()) {
                var archived = storage.storeVideo(context.lease().projectId(), UUID.randomUUID(), input);
                silentOutput[0] = storage.checkedPath(archived.objectKey());
                assertThat(archived.durationMs()).isEqualTo(600_000);
                return new Asset(UUID.randomUUID(), context.lease().projectId(), Asset.MediaKind.VIDEO,
                        archived.objectKey(), "video/mp4", archived.byteSize(), archived.sha256(),
                        archived.width(), archived.height(), archived.durationMs(), archived.thumbnailKey(),
                        archived.thumbnailByteSize(), archived.thumbnailSha256(), Instant.now());
            }
        });

        assertThat(processor.preflight(context, VideoOperation.EXTRACT_AUDIO)).isNull();
        var submission = processor.submit(context, VideoOperation.EXTRACT_AUDIO);
        assertThat(submission).isInstanceOfSatisfying(Submission.CompletedArtifact.class, completed -> {
            assertThat(completed.content().at("/parameters/format").asText()).isEqualTo("MP3");
            assertThat(completed.content().at("/parameters/trackIndex").asInt()).isEqualTo(1);
        });
        assertThat(audioOutput[0].durationMs()).isBetween(600_000, 600_100);
        assertThat(audioOutput[0].byteSize()).isLessThan(15_000_000);
        assertThat(audioOutput[0].contentType()).isEqualTo("audio/mpeg");
        assertThat(frequency(storage.checkedPath(audioOutput[0].objectKey()))).isBetween(830d, 930d);
        processor.silentVideoResult(context);
        assertThat(streamHash(silentOutput[0], "silent")).isEqualTo(streamHash(source, "source"));
        assertThat(mapper.readTree(tools.ffprobe(List.of("-v", "error", "-select_streams", "a", "-show_entries",
                "stream=index", "-of", "json", silentOutput[0].toString()))).path("streams")).isEmpty();
    }

    @Test
    void missingSelectedTrackBlocksBeforeProcessing() {
        var context = context(source(1), 1000, "WAV", 2);
        assertThat(processor().preflight(context, VideoOperation.EXTRACT_AUDIO)).isEqualTo("VIDEO_AUDIO_TRACK_MISSING");
    }

    private LocalVideoProcessor processor() {
        return new LocalVideoProcessor(artifacts, assets, tools, mock(LocalImageProcessorAdapter.class), tasks, mapper);
    }

    private AttemptContext context(Path source, int durationMs, String format, int trackIndex) {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), versionId = UUID.randomUUID(), assetId = UUID.randomUUID();
        Task task = mock(Task.class);
        when(task.id()).thenReturn(UUID.randomUUID());
        when(task.projectId()).thenReturn(project);
        var input = mapper.createObjectNode();
        input.putObject("videoOperation").put("sourceVersionId", versionId.toString());
        input.putObject("mediaInput").putObject("parameters").put("format", format).put("trackIndex", trackIndex);
        when(task.input()).thenReturn(input);
        when(tasks.get(owner, project, task.id())).thenReturn(task);
        var version = mock(ArtifactVersion.class);
        when(version.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(artifacts.requireMediaVersionForTask(owner, project, versionId, Artifact.Kind.VIDEO)).thenReturn(version);
        try {
            var asset = new Asset(assetId, project, Asset.MediaKind.VIDEO, "synthetic.mp4", "video/mp4", Files.size(source),
                    "synthetic", 16, 16, durationMs, null, null, null, Instant.now());
            when(assets.get(owner, project, assetId)).thenReturn(new AssetService.AssetFile(asset, source));
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        return new AttemptContext(task, null, owner, "synthetic-request", null);
    }

    private Path source(int seconds) {
        Path output = root.resolve("synthetic-source-" + seconds + ".mp4");
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "color=c=blue:s=16x16:r=1",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000",
                "-t", Integer.toString(seconds), "-map", "0:v:0", "-map", "1:a:0", "-map", "2:a:0", "-c:v", "libx264",
                "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "16k", "-y", output.toString()));
        return output;
    }

    private double frequency(Path audio) throws Exception {
        Path decoded = root.resolve("selected-track.f32");
        tools.ffmpeg(List.of("-v", "error", "-nostdin", "-i", audio.toString(), "-t", "0.2", "-ac", "1",
                "-ar", "48000", "-c:a", "pcm_f32le", "-f", "f32le", "-y", decoded.toString()));
        var samples = ByteBuffer.wrap(Files.readAllBytes(decoded)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        int count = samples.remaining(), crossings = 0;
        float previous = samples.get();
        while (samples.hasRemaining()) {
            float current = samples.get();
            if (previous < 0 && current >= 0) crossings++;
            previous = current;
        }
        return crossings * 48_000d / count;
    }

    private String streamHash(Path video, String name) throws Exception {
        Path hash = root.resolve(name + ".hash");
        tools.ffmpeg(List.of("-v", "error", "-nostdin", "-i", video.toString(), "-map", "0:v:0", "-c:v", "copy",
                "-f", "hash", "-hash", "sha256", "-y", hash.toString()));
        return Files.readString(hash);
    }
}
