package dev.agenvas.subtitle.application;

import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.subtitle.domain.SubtitleFiles;
import dev.agenvas.subtitle.domain.SubtitleTrack;
import dev.agenvas.subtitle.domain.SubtitleCue;
import dev.agenvas.subtitle.infrastructure.LocalAsrProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/** Real local speech recognition; it never derives dialogue from volume, scripts or a cloud service. */
@Component
public class WhisperCppTranscriber {
    private static final int MAX_RECOGNITION_TAIL_OVERRUN_MS = 100;
    public enum Availability { AVAILABLE, NOT_CONFIGURED, EXECUTABLE_UNAVAILABLE, MODEL_UNAVAILABLE }
    /** Paths are resolved by an authorized application service, never accepted from API/model input. */
    public record Source(UUID versionId, Path path, int durationMs) {}
    private final MediaToolRunner mediaTools;
    private final LocalAsrProperties properties;
    private final LocalAsrProcessRunner processes;
    // Native model memory is in addition to the JVM. Deliberately fixed to one job per instance.
    private final Semaphore slot = new Semaphore(1);

    public WhisperCppTranscriber(MediaToolRunner mediaTools, LocalAsrProperties properties, LocalAsrProcessRunner processes) {
        this.mediaTools = mediaTools; this.properties = properties; this.processes = processes;
    }

    /** Whisper frames can extend a final cue slightly beyond the decoded media boundary.
     * Only native recognition gets this bounded tolerance; user-imported files stay strict. */
    static SubtitleTrack decodeRecognitionOutput(String contents, Source source, String language) {
        var decoded = SubtitleFiles.decode(contents, SubtitleFiles.Format.SRT,
                source.versionId(), source.durationMs() + MAX_RECOGNITION_TAIL_OVERRUN_MS, language);
        var cues = decoded.cues().stream().map(cue -> new SubtitleCue(cue.text(),
                cue.startMs(), Math.min(cue.endMs(), source.durationMs()))).toList();
        return new SubtitleTrack(source.versionId(), source.durationMs(), language, cues);
    }

    /** Checks local installation only; does not launch an engine, contact a registry or fetch weights. */
    public Availability availability() {
        if (properties.executable() == null || properties.model() == null) return Availability.NOT_CONFIGURED;
        if (!Files.isRegularFile(properties.executable()) || !Files.isExecutable(properties.executable()))
            return Availability.EXECUTABLE_UNAVAILABLE;
        try {
            if (!Files.isRegularFile(properties.model()) || !Files.isReadable(properties.model()) || Files.size(properties.model()) == 0)
                return Availability.MODEL_UNAVAILABLE;
        } catch (IOException unavailable) { return Availability.MODEL_UNAVAILABLE; }
        return Availability.AVAILABLE;
    }

    /** Produces a draft bound to the original version. Upstream must freeze and authorize that version. */
    public SubtitleTrack transcribe(Source source, String language, BooleanSupplier canceled, Runnable heartbeat) {
        return transcribe(source, language, canceled, heartbeat, () -> {});
    }

    /** Managed installations recheck memory after decoding, immediately before native model loading. */
    public SubtitleTrack transcribe(Source source, String language, BooleanSupplier canceled, Runnable heartbeat, Runnable beforeInference) {
        Availability available = availability();
        if (available != Availability.AVAILABLE) throw new LocalAsrException(available == Availability.NOT_CONFIGURED
                ? LocalAsrException.Code.NOT_CONFIGURED : LocalAsrException.Code.UNAVAILABLE);
        if (source == null || source.path() == null || !source.path().isAbsolute() || !Files.isRegularFile(source.path())
                || source.durationMs() < 1 || source.durationMs() > LocalAsrProperties.MAX_SOURCE_DURATION_MS)
            throw new LocalAsrException(LocalAsrException.Code.INVALID_SOURCE);
        new SubtitleTrack(source.versionId(), source.durationMs(), language, List.of());
        if (canceled == null || heartbeat == null) throw new IllegalArgumentException("Missing local ASR lifecycle callbacks");
        if (!slot.tryAcquire()) throw new LocalAsrException(LocalAsrException.Code.BUSY);
        Path scratch = null;
        try {
            long deadline = System.nanoTime() + properties.timeout().toNanos();
            scratch = Files.createTempDirectory("agenvas-subtitles-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path work = scratch;
            Runnable tick = () -> { LocalAsrProcessRunner.check(work, deadline, canceled); heartbeat.run(); };
            tick.run();
            String stream = mediaTools.ffprobeExport(List.of("-v", "error", "-protocol_whitelist", "file,pipe",
                    "-select_streams", "a:0", "-show_entries", "stream=codec_type", "-of", "default=noprint_wrappers=1:nokey=1",
                    source.path().toString()), scratch, remaining(deadline), canceled, tick);
            tick.run();
            if (stream.isBlank()) throw new LocalAsrException(LocalAsrException.Code.NO_AUDIO);
            Path wav = scratch.resolve("input.wav");
            Duration decodeTimeout = remaining(deadline);
            if (decodeTimeout.compareTo(Duration.ofMinutes(3)) > 0) decodeTimeout = Duration.ofMinutes(3);
            mediaTools.ffmpegExport(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-threads", "2",
                    "-protocol_whitelist", "file,pipe", "-i", source.path().toString(), "-map", "0:a:0", "-vn",
                    "-t", "600.100", "-af", "aresample=async=1:first_pts=0", "-ac", "1", "-ar", "16000",
                    "-c:a", "pcm_s16le", "-map_metadata", "-1", "-y", wav.toString()),
                    scratch, decodeTimeout, canceled, tick);
            tick.run();
            String measured = mediaTools.ffprobeExport(List.of("-v", "error", "-protocol_whitelist", "file,pipe",
                    "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", wav.toString()),
                    scratch, remaining(deadline), canceled, tick);
            double decodedMs = Double.parseDouble(measured.strip()) * 1000;
            if (!Double.isFinite(decodedMs) || decodedMs <= 0 || decodedMs > LocalAsrProperties.MAX_SOURCE_DURATION_MS
                    || decodedMs > source.durationMs() + 100)
                throw new LocalAsrException(LocalAsrException.Code.INVALID_SOURCE);
            tick.run();
            beforeInference.run();
            processes.run(properties.executable(), List.of("--model", properties.model().toString(), "--file", wav.toString(),
                    "--language", language, "--threads", properties.threads().toString(), "--processors", "1", "--no-gpu",
                    "--max-len", "160", "--split-on-word", "--output-srt", "--output-file", scratch.resolve("transcript").toString()),
                    scratch, remaining(deadline), canceled, tick);
            tick.run();
            Path output = scratch.resolve("transcript.srt");
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) > SubtitleFiles.MAX_FILE_BYTES)
                throw new LocalAsrException(LocalAsrException.Code.INVALID_OUTPUT);
            SubtitleTrack result;
            try {
                result = decodeRecognitionOutput(Files.readString(output), source, language);
            } catch (IllegalArgumentException invalid) { throw new LocalAsrException(LocalAsrException.Code.INVALID_OUTPUT); }
            tick.run();
            return result;
        } catch (MediaToolRunner.MediaToolException failure) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Local ASR interrupted");
            if (failure.getMessage().contains("timed out")) throw new LocalAsrException(LocalAsrException.Code.TIMED_OUT);
            throw new LocalAsrException(failure.invalidInput() ? LocalAsrException.Code.INVALID_SOURCE : LocalAsrException.Code.FAILED);
        } catch (IOException | NumberFormatException failure) {
            throw new LocalAsrException(LocalAsrException.Code.FAILED);
        } finally {
            try { cleanup(scratch); } finally { slot.release(); }
        }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new LocalAsrException(LocalAsrException.Code.TIMED_OUT);
        return Duration.ofNanos(nanos);
    }

    private static void cleanup(Path scratch) {
        if (scratch == null) return;
        try (var paths = Files.walk(scratch)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger(WhisperCppTranscriber.class).warn("Local subtitle scratch cleanup failed");
        }
    }
}
