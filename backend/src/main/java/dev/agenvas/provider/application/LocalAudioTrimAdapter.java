package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.AudioExtractionParameters;
import dev.agenvas.task.domain.AudioTrimParameters;
import dev.agenvas.task.domain.Task;
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
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Fixed local audio editing with a pinned source, bounded private scratch, and decoded-length verification. */
@Component
public class LocalAudioTrimAdapter implements MediaAdapter {
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final MediaToolRunner tools;
    private final TaskService tasks;
    private final ObjectMapper mapper;

    public LocalAudioTrimAdapter(ArtifactService artifacts, AssetService assets, MediaToolRunner tools,
            TaskService tasks, ObjectMapper mapper) {
        this.artifacts = artifacts; this.assets = assets; this.tools = tools; this.tasks = tasks; this.mapper = mapper;
    }

    @Override public String adapterId() { return MediaAdapterRegistry.LOCAL_AUDIO_TRIMMER; }
    @Override public boolean supports(PortInput input) { return input.kind() == Task.Kind.AUDIO_GENERATION; }
    @Override public String preflightFailure(AttemptContext context) {
        try { source(context); return null; }
        catch (ApiProblemException invalid) {
            return "AUDIO_TRIM_OUTPUT_TOO_LARGE".equals(invalid.code()) ? invalid.code() : "AUDIO_TRIM_INVALID";
        }
        catch (RuntimeException invalid) { return "AUDIO_TRIM_INVALID"; }
    }
    @Override public Submission reconcile(AttemptContext context) {
        return new Submission.Blocked("LOCAL_AUDIO_HAS_NO_EXTERNAL_REQUEST");
    }

    @Override public Submission submit(AttemptContext context) {
        Path scratch = null;
        try {
            var source = source(context);
            var parameters = parameters(context);
            scratch = Files.createTempDirectory("agenvas-audio-trim-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var rendered = render(source.path(), source.asset().durationMs(), parameters, scratch, context);
            Path output = rendered.path();
            tick(context);
            var task = context.lease();
            var asset = assets.archiveTaskAudio(context.ownerId(), task.projectId(), task.id(), () -> {
                try { return Files.newInputStream(output); }
                catch (IOException failure) { throw new IllegalStateException("Cannot read trimmed audio", failure); }
            });
            var content = MediaResult.content(mapper, task, asset.id(), task.input().path("prompt").asText());
            var result = MediaResult.copyFrozenParameters(content, task);
            result.put("operation", "AUDIO_TRIM").put("local", true)
                    .put("sourceVersionId", task.input().path("audioTrim").path("sourceVersionId").asText())
                    .put("startMs", parameters.startMs()).put("endMs", rendered.endMs())
                    .put("requestedEndMs", parameters.endMs())
                    .put("format", parameters.format().name()).put("sampleRate", AudioExtractionParameters.SAMPLE_RATE)
                    .put("channels", AudioExtractionParameters.CHANNELS);
            if (parameters.format() == AudioExtractionParameters.Format.MP3)
                result.put("bitrateKbps", AudioExtractionParameters.MP3_BITRATE_KBPS);
            return new Submission.CompletedArtifact(content);
        } catch (ApiProblemException invalid) { return new Submission.Rejected(invalid.code()); }
        catch (ProcessingTimeout timeout) { return new Submission.Rejected("LOCAL_AUDIO_TRIM_TIMEOUT"); }
        catch (CancellationException canceled) { return new Submission.Rejected("LOCAL_AUDIO_TRIM_CANCELED"); }
        catch (RuntimeException | IOException failure) { return new Submission.Rejected("LOCAL_AUDIO_TRIM_FAILED"); }
        finally {
            if (scratch != null) {
                try (var paths = Files.walk(scratch)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException failure) {
                    org.slf4j.LoggerFactory.getLogger(LocalAudioTrimAdapter.class).warn("Audio trim scratch cleanup failed");
                }
            }
        }
    }

    private AssetService.AssetFile source(AttemptContext context) {
        if (!context.lease().isAudioTrim()) throw AudioTrimParameters.invalid();
        var parameters = parameters(context);
        var input = context.lease().input();
        UUID versionId = UUID.fromString(input.path("audioTrim").path("sourceVersionId").asText());
        var version = artifacts.requireMediaVersionForTask(context.ownerId(), context.lease().projectId(), versionId, Artifact.Kind.AUDIO);
        var frozen = input.path("mediaInput").path("audios");
        if (!frozen.isArray() || frozen.size() != 1
                || !versionId.toString().equals(frozen.get(0).path("versionId").asText())
                || !version.artifactId().toString().equals(frozen.get(0).path("artifactId").asText())
                || !parameters.toJson(mapper).equals(input.path("mediaInput").path("parameters")))
            throw AudioTrimParameters.invalid();
        var file = assets.get(context.ownerId(), context.lease().projectId(), UUID.fromString(version.content().path("assetId").asText()));
        parameters.requireSource(file.asset());
        return file;
    }

    private AudioTrimParameters parameters(AttemptContext context) {
        return AudioTrimParameters.parse(context.lease().input().path("audioTrim").path("parameters"));
    }

    /** Resample before sample-based trimming so millisecond boundaries remain exact for any source sample rate. */
    private record Rendered(Path path, double endMs) {}

    private Rendered render(Path source, int sourceDurationMs, AudioTrimParameters parameters,
            Path scratch, AttemptContext context) throws IOException {
        tick(context);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        long expectedPcmBytes = (long) parameters.durationMs() * AudioExtractionParameters.SAMPLE_RATE
                * AudioExtractionParameters.CHANNELS * Short.BYTES / 1000;
        // The optional source verification also fits inside a fixed, duration-derived private budget.
        long sourcePcmBudget = (sourceDurationMs + 300L) * AudioExtractionParameters.SAMPLE_RATE
                * AudioExtractionParameters.CHANNELS * Short.BYTES / 1000;
        long scratchBudget = AudioTrimParameters.MAX_OUTPUT_BYTES + expectedPcmBytes + sourcePcmBudget + 4096;
        boolean wav = parameters.format() == AudioExtractionParameters.Format.WAV;
        Path output = scratch.resolve(wav ? "trimmed.wav" : "trimmed.mp3");
        var command = inputArguments(source);
        long samplesPerMs = AudioExtractionParameters.SAMPLE_RATE / 1000;
        command.addAll(List.of("-map", "0:a:0", "-vn", "-map_metadata", "-1", "-map_chapters", "-1",
                "-af", "aresample=" + AudioExtractionParameters.SAMPLE_RATE
                        + ",atrim=start_sample=" + parameters.startMs() * samplesPerMs
                        + ":end_sample=" + parameters.endMs() * samplesPerMs + ",asetpts=PTS-STARTPTS",
                "-ac", Integer.toString(AudioExtractionParameters.CHANNELS), "-ar", Integer.toString(AudioExtractionParameters.SAMPLE_RATE)));
        command.addAll(wav ? List.of("-c:a", "pcm_s16le")
                : List.of("-c:a", "libmp3lame", "-b:a", AudioExtractionParameters.MP3_BITRATE_KBPS + "k"));
        command.addAll(List.of("-y", output.toString()));
        // Never use -fs or padding. Ordinary ranges must be exact; explicit source-end selections may
        // use only the separately verified decoded EOF when container timing includes rounding or padding.
        run(command, scratch, output, deadline, scratchBudget, context);
        var streams = mapper.readTree(tools.ffprobe(List.of("-v", "error", "-protocol_whitelist", "file,pipe",
                "-show_entries", "stream=codec_type,codec_name,sample_rate,channels", "-of", "json", output.toString())))
                .path("streams");
        if (!streams.isArray() || streams.size() != 1 || !"audio".equals(streams.get(0).path("codec_type").asText())
                || !(wav ? "pcm_s16le" : "mp3").equals(streams.get(0).path("codec_name").asText())
                || streams.get(0).path("sample_rate").asInt() != AudioExtractionParameters.SAMPLE_RATE
                || streams.get(0).path("channels").asInt() != AudioExtractionParameters.CHANNELS)
            throw new IllegalStateException("Unexpected trimmed audio encoding");
        tick(context);
        Path verification = scratch.resolve("verify.pcm");
        command = inputArguments(output);
        command.addAll(List.of("-map", "0:a:0", "-vn", "-c:a", "pcm_s16le", "-f", "s16le", "-y", verification.toString()));
        run(command, scratch, output, deadline, scratchBudget, context);
        long decodedBytes = Files.size(verification);
        if (decodedBytes != expectedPcmBytes) {
            verifySourceEnd(source, sourceDurationMs, parameters, decodedBytes, expectedPcmBytes,
                    scratch, output, deadline, scratchBudget, context);
        }
        Files.delete(verification);
        double actualEndMs = parameters.startMs() + decodedBytes * 1000.0
                / (AudioExtractionParameters.SAMPLE_RATE * AudioExtractionParameters.CHANNELS * Short.BYTES);
        return new Rendered(output, actualEndMs);
    }

    /** Container milliseconds can round above the playable EOF, and historic MP3 metadata can include
     * encoder padding. Only an explicit selection of the source end may use its verified decoded EOF.
     * A second full source decode proves the output did not lose samples during rendering. */
    private void verifySourceEnd(Path source, int sourceDurationMs, AudioTrimParameters parameters,
            long decodedBytes, long expectedBytes, Path scratch, Path output, long deadline,
            long scratchBudget, AttemptContext context) throws IOException {
        long bytesPerMillisecond = AudioExtractionParameters.SAMPLE_RATE * AudioExtractionParameters.CHANNELS * Short.BYTES / 1000;
        if (parameters.endMs() != sourceDurationMs || decodedBytes < AudioTrimParameters.MIN_DURATION_MS * bytesPerMillisecond
                || decodedBytes > expectedBytes) throw incompleteOutput();
        var sourceStreams = mapper.readTree(tools.ffprobe(List.of("-v", "error", "-protocol_whitelist", "file,pipe",
                "-show_entries", "stream=codec_name", "-of", "json", source.toString()))).path("streams");
        if (!sourceStreams.isArray() || sourceStreams.size() != 1) throw incompleteOutput();
        String codec = sourceStreams.get(0).path("codec_name").asText();
        long maximumDifference = ("mp3".equals(codec) || "opus".equals(codec) ? 300 : 1) * bytesPerMillisecond;
        if (expectedBytes - decodedBytes > maximumDifference) throw incompleteOutput();
        Path sourcePcm = scratch.resolve("source-verify.pcm");
        var command = inputArguments(source);
        command.addAll(List.of("-xerror", "-map", "0:a:0", "-vn", "-af", "aresample=" + AudioExtractionParameters.SAMPLE_RATE,
                "-ac", Integer.toString(AudioExtractionParameters.CHANNELS), "-ar", Integer.toString(AudioExtractionParameters.SAMPLE_RATE),
                "-c:a", "pcm_s16le", "-f", "s16le", "-y", sourcePcm.toString()));
        run(command, scratch, output, deadline, scratchBudget, context);
        long startBytes = parameters.startMs() * bytesPerMillisecond;
        if (Files.size(sourcePcm) - startBytes != decodedBytes) throw incompleteOutput();
        Files.delete(sourcePcm);
    }

    private static IllegalStateException incompleteOutput() {
        return new IllegalStateException("Trimmed audio does not contain the complete requested sample range");
    }

    private static ArrayList<String> inputArguments(Path source) {
        return new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-protocol_whitelist", "file,pipe",
                "-threads", "2", "-filter_threads", "2", "-i", source.toString()));
    }

    private void run(List<String> arguments, Path scratch, Path output, long deadline, long maxBytes, AttemptContext context) {
        Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
        if (remaining.isNegative() || remaining.isZero()) throw new ProcessingTimeout();
        try {
            tools.ffmpegExport(arguments, scratch, remaining,
                    () -> tasks.get(context.ownerId(), context.lease().projectId(), context.lease().id()).cancelRequested(),
                    () -> { tick(context); checkScratch(scratch, output, maxBytes); });
            checkScratch(scratch, output, maxBytes);
            tick(context);
        } catch (RuntimeException failure) {
            if (System.nanoTime() >= deadline) throw new ProcessingTimeout();
            throw failure;
        }
    }

    private void checkScratch(Path scratch, Path output, long maxBytes) {
        try (var files = Files.list(scratch)) {
            if (Files.exists(output) && Files.size(output) > AudioTrimParameters.MAX_OUTPUT_BYTES)
                throw AudioTrimParameters.outputTooLarge();
            long bytes = 0;
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                bytes += Files.size(path);
                if (bytes > maxBytes) throw AudioTrimParameters.outputTooLarge();
            }
        } catch (IOException failure) { throw new IllegalStateException("Cannot inspect audio trim scratch", failure); }
    }

    private void tick(AttemptContext context) {
        var task = context.lease();
        if (tasks.get(context.ownerId(), task.projectId(), task.id()).cancelRequested()) throw new CancellationException();
        tasks.heartbeat(task.id(), task.leaseOwner(), task.leaseEpoch());
    }
    private static final class ProcessingTimeout extends RuntimeException {}
}
