package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.application.MediaToolsProperties;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.AudioExtractionParameters.Format;
import dev.agenvas.task.domain.AudioTrimParameters;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Exercises real FFmpeg with generated audio; no external model or private source is involved. */
class LocalAudioTrimAdapterTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final TaskService tasks = mock(TaskService.class);
    private MediaToolRunner tools;
    private LocalAudioTrimAdapter adapter;

    @BeforeEach void setup() {
        var properties = new MediaToolsProperties(null, null, null);
        assumeTrue(Files.isExecutable(properties.ffmpeg()) && Files.isExecutable(properties.ffprobe()), "FFmpeg and ffprobe required");
        tools = new MediaToolRunner(properties);
        adapter = new LocalAudioTrimAdapter(artifacts, assets, tools, tasks, mapper);
    }

    @ParameterizedTest @EnumSource(Format.class)
    void trimsTheChosenIntervalPreservingInternalSilenceAndExactDecodedLength(Format format) throws Exception {
        Path source = signal("source.wav", "2", "if(between(t,0.6,0.8),0,0.1*sin(2*PI*440*t))", 44100);
        var context = context(source, 2000, new AudioTrimParameters(300, 1300, format));
        Path archived = captureArchive(context, format);
        assertThat(adapter.preflightFailure(context)).isNull();
        var result = adapter.submit(context);
        assertThat(result).isInstanceOf(Submission.CompletedArtifact.class);
        var parameters = ((Submission.CompletedArtifact) result).content().path("parameters");
        assertThat(parameters.path("startMs").asInt()).isEqualTo(300);
        assertThat(parameters.path("endMs").asInt()).isEqualTo(1300);
        byte[] pcm = decode(archived);
        assertThat(pcm).hasSize(48_000 * 2 * 2);
        assertThat(rms(pcm, 350, 450)).isLessThan(0.001);
        assertThat(rms(pcm, 100, 200)).isGreaterThan(0.02);
        assertThat(rms(pcm, 700, 800)).isGreaterThan(0.02);
        assertThat(Files.exists(source)).isTrue();
    }

    @ParameterizedTest @EnumSource(Format.class)
    void acceptsTheShortestHundredMillisecondSample(Format format) throws Exception {
        var context = context(signal("source.wav", "1", "0.1*sin(2*PI*440*t)", 48000),
                1000, new AudioTrimParameters(100, 200, format));
        Path archived = captureArchive(context, format);
        assertThat(adapter.submit(context)).isInstanceOf(Submission.CompletedArtifact.class);
        assertThat(decode(archived)).hasSize(4800 * 2 * 2);
    }

    @Test void incompleteSourceCannotBecomeASuccessfulShortOutput() throws Exception {
        var context = context(signal("source.wav", "0.5", "0.1*sin(2*PI*440*t)", 48000),
                2000, new AudioTrimParameters(0, 1000, Format.WAV));
        assertThat(adapter.submit(context)).isEqualTo(new Submission.Rejected("LOCAL_AUDIO_TRIM_FAILED"));
        verify(assets, never()).archiveTaskAudio(any(), any(), any(), any());
    }

    @ParameterizedTest @EnumSource(Format.class)
    void wholeMp3WithoutXingUsesVerifiedEofWhenMetadataRoundedUp(Format format) throws Exception {
        Path source = mp3("without-xing.mp3", "0.1", 0);
        int durationMs = (int) Math.round(mapper.readTree(tools.ffprobe(List.of("-v", "error", "-show_entries",
                "format=duration", "-of", "json", source.toString()))).path("format").path("duration").asDouble() * 1000);
        var context = context(source, durationMs, new AudioTrimParameters(0, durationMs, format));
        Path archived = captureArchive(context, format);
        var result = adapter.submit(context);
        assertThat(result).isInstanceOf(Submission.CompletedArtifact.class);
        var parameters = ((Submission.CompletedArtifact) result).content().path("parameters");
        assertThat(parameters.path("requestedEndMs").asInt()).isEqualTo(durationMs);
        assertThat(parameters.path("endMs").asDouble()).isLessThanOrEqualTo(durationMs);
        byte[] actual = decode(archived);
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-i", source.toString(), "-ac", "2", "-ar", "48000",
                "-c:a", "pcm_s16le", "-f", "s16le", "-y", temp.resolve("source.pcm").toString()));
        assertThat(actual).hasSize((int) Files.size(temp.resolve("source.pcm")));
    }

    @Test void wholeGaplessMp3SupportsHistoricDurationIncludingEncoderPadding() throws Exception {
        Path source = mp3("xing.mp3", "1", 1);
        // Older probes can report a container duration that includes MP3 encoder padding.
        var context = context(source, 1045, new AudioTrimParameters(0, 1045, Format.WAV));
        Path archived = captureArchive(context, Format.WAV);
        var result = adapter.submit(context);
        assertThat(result).isInstanceOf(Submission.CompletedArtifact.class);
        var parameters = ((Submission.CompletedArtifact) result).content().path("parameters");
        assertThat(parameters.path("requestedEndMs").asInt()).isEqualTo(1045);
        assertThat(parameters.path("endMs").asDouble()).isEqualTo(1000);
        assertThat(decode(archived)).hasSize(48_000 * 4);
    }

    @Test void endSelectionCannotHideLargeMissingSourceContent() throws Exception {
        var context = context(mp3("short.mp3", "0.5", 1), 1000, new AudioTrimParameters(0, 1000, Format.MP3));
        assertThat(adapter.submit(context)).isEqualTo(new Submission.Rejected("LOCAL_AUDIO_TRIM_FAILED"));
        verify(assets, never()).archiveTaskAudio(any(), any(), any(), any());
    }

    @Test void cancellationNeverArchivesAndLeavesSourceIntact() throws Exception {
        Path source = signal("source.wav", "1", "0.1*sin(2*PI*440*t)", 48000);
        var context = context(source, 1000, new AudioTrimParameters(0, 1000, Format.WAV));
        Task canceled = mock(Task.class);
        when(canceled.cancelRequested()).thenReturn(true);
        when(tasks.get(context.ownerId(), context.lease().projectId(), context.lease().id())).thenReturn(canceled);
        assertThat(adapter.submit(context)).isEqualTo(new Submission.Rejected("LOCAL_AUDIO_TRIM_CANCELED"));
        verify(assets, never()).archiveTaskAudio(any(), any(), any(), any());
        assertThat(Files.exists(source)).isTrue();
    }

    @Test void rejectsUnpinnedOrChangedEncoderInputBeforeStarting() throws Exception {
        var context = context(signal("source.wav", "1", "0.1*sin(2*PI*440*t)", 48000),
                1000, new AudioTrimParameters(0, 1000, Format.WAV));
        ((ObjectNode) context.lease().input()).withObject("mediaInput").withObject("parameters").put("format", "MP3");
        assertThat(adapter.preflightFailure(context)).isEqualTo("AUDIO_TRIM_INVALID");
        assertThat(adapter.submit(context)).isEqualTo(new Submission.Rejected("AUDIO_TRIM_INVALID"));
        verify(assets, never()).archiveTaskAudio(any(), any(), any(), any());
    }

    @Test void unavailableSourceUsesAStableSupportedPreflightCode() throws Exception {
        var context = context(signal("source.wav", "1", "0.1*sin(2*PI*440*t)", 48000),
                1000, new AudioTrimParameters(0, 1000, Format.WAV));
        when(artifacts.requireMediaVersionForTask(eq(context.ownerId()), eq(context.lease().projectId()), any(), eq(Artifact.Kind.AUDIO)))
                .thenThrow(new ApiProblemException(org.springframework.http.HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                        ApiMessage.of("api.audio-trim.invalid"), ApiMessage.of("api.audio-trim.invalid"), false));
        assertThat(adapter.preflightFailure(context)).isEqualTo("AUDIO_TRIM_INVALID");
        verify(assets, never()).archiveTaskAudio(any(), any(), any(), any());
    }

    private AttemptContext context(Path source, int duration, AudioTrimParameters parameters) throws Exception {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), artifactId = UUID.randomUUID(),
                versionId = UUID.randomUUID(), assetId = UUID.randomUUID();
        var input = mapper.createObjectNode().put("prompt", "音频裁剪");
        input.putObject("audioTrim").put("sourceVersionId", versionId.toString()).set("parameters", parameters.toJson(mapper));
        var frozen = input.putObject("mediaInput");
        frozen.set("parameters", parameters.toJson(mapper));
        frozen.putArray("audios").addObject().put("versionId", versionId.toString()).put("artifactId", artifactId.toString());
        var now = Instant.now();
        var task = new Task(UUID.randomUUID(), project, null, "test", Task.Kind.AUDIO_GENERATION,
                Task.Status.SUBMITTING, false, input, "hash", null, null, 1, now, "worker", now.plusSeconds(60), 1, 0, null, now, now, null);
        var version = mock(ArtifactVersion.class);
        when(version.artifactId()).thenReturn(artifactId);
        when(version.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(artifacts.requireMediaVersionForTask(owner, project, versionId, Artifact.Kind.AUDIO)).thenReturn(version);
        var asset = new Asset(assetId, project, Asset.MediaKind.AUDIO, "synthetic", "audio/wav", Files.size(source), "hash",
                null, null, duration, null, null, null, now);
        when(assets.get(owner, project, assetId)).thenReturn(new AssetService.AssetFile(asset, source));
        when(tasks.get(owner, project, task.id())).thenReturn(task);
        return new AttemptContext(task, null, owner, "synthetic", null);
    }

    private Path captureArchive(AttemptContext context, Format format) {
        Path target = temp.resolve("archive." + format.name().toLowerCase(java.util.Locale.ROOT));
        var asset = mock(Asset.class);
        when(asset.id()).thenReturn(UUID.randomUUID());
        when(assets.archiveTaskAudio(eq(context.ownerId()), eq(context.lease().projectId()), eq(context.lease().id()), any()))
                .thenAnswer(call -> {
                    Supplier<java.io.InputStream> content = call.getArgument(3);
                    try (var stream = content.get()) { Files.copy(stream, target); }
                    return asset;
                });
        return target;
    }

    private Path signal(String name, String duration, String expression, int rate) {
        Path target = temp.resolve(name);
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "aevalsrc=" + expression.replace(",", "\\,") + ":s=" + rate + ":d=" + duration,
                "-c:a", "pcm_s16le", "-y", target.toString()));
        return target;
    }

    private Path mp3(String name, String duration, int xing) {
        Path target = temp.resolve(name);
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=44100:duration=" + duration,
                "-c:a", "libmp3lame", "-b:a", "192k", "-write_xing", Integer.toString(xing), "-y", target.toString()));
        return target;
    }

    private byte[] decode(Path file) throws Exception {
        Path target = temp.resolve("decoded.pcm");
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-i", file.toString(), "-c:a", "pcm_s16le", "-f", "s16le", "-y", target.toString()));
        return Files.readAllBytes(target);
    }

    private double rms(byte[] samples, int fromMs, int untilMs) {
        double total = 0; int count = 0;
        for (int i = fromMs * 48 * 4; i < untilMs * 48 * 4; i += 2) {
            double sample = (short) ((samples[i] & 255) | samples[i + 1] << 8) / 32768.0;
            total += sample * sample; count++;
        }
        return Math.sqrt(total / count);
    }
}
