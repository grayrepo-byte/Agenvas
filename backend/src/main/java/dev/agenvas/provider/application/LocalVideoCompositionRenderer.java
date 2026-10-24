package dev.agenvas.provider.application;

import dev.agenvas.asset.infrastructure.MediaToolRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Owns local decoding and editing only. Its caller resolves authorized immutable versions to paths. */
@Component
public class LocalVideoCompositionRenderer {
    static final Duration TIMEOUT = Duration.ofMinutes(3);
    static final long MAX_SCRATCH_BYTES = 512L * 1024 * 1024;
    record Source(Path path, int durationMs) {}
    record Result(Path path, ObjectNode schedule) {}
    private final MediaToolRunner tools;
    private final ObjectMapper mapper;
    private final VideoCompositionRenderProperties properties;
    private final AudioActivityAnalyzer analyzer = new AudioActivityAnalyzer();
    private final VideoCompositionPlanner planner = new VideoCompositionPlanner();

    public LocalVideoCompositionRenderer(MediaToolRunner tools, ObjectMapper mapper) {
        this(tools, mapper, new VideoCompositionRenderProperties(null));
    }

    @Autowired
    public LocalVideoCompositionRenderer(MediaToolRunner tools, ObjectMapper mapper, VideoCompositionRenderProperties properties) {
        this.tools = tools; this.mapper = mapper; this.properties = properties;
    }

    Result render(JsonNode request, Map<UUID, Source> sources, Path scratch,
            BooleanSupplier canceled, Runnable heartbeat) throws IOException {
        Budget budget = new Budget(scratch, canceled, heartbeat);
        var prepared = prepare(request, sources, scratch, budget);
        var plan = prepared.plan();
        var sourceHasAudio = prepared.hasAudio();
        var outputSettings = outputSettings(request.path("output"));
        List<String> arguments = arguments();
        List<String> graph = new ArrayList<>();
        List<String> mixed = new ArrayList<>();
        String duration = decimal(plan.frames() / (double) VideoCompositionPlanner.FPS);
        graph.add("anullsrc=r=48000:cl=stereo:d=" + duration + "[bed]");
        mixed.add("[bed]");
        ObjectNode schedule = mapper.createObjectNode().put("title", request.path("title").asText())
                .put("rationale", request.path("rationale").asText()).put("originalAudioMode", request.path("originalAudioMode").asText("MUTE"))
                .put("width", outputSettings.width()).put("height", outputSettings.height()).put("frameRate", VideoCompositionPlanner.FPS)
                .put("durationMs", plan.durationMs());
        schedule.putObject("output").put("resolution", outputSettings.resolution()).put("aspectRatio", outputSettings.aspectRatio());
        var warnings = schedule.putArray("warnings");
        plan.warnings().forEach(warnings::add);
        boolean background = "BACKGROUND".equals(request.path("originalAudioMode").asText());
        boolean keepOriginal = background || "KEEP".equals(request.path("originalAudioMode").asText());
        if (background) warnings.add("ORIGINAL_AUDIO_AT_0_15_INCLUDES_ANY_ORIGINAL_DIALOGUE");
        var shotResults = schedule.putArray("shots");
        int inputIndex = 0, lineIndex = 0, lastDialogueEndMs = 0;
        for (int shotIndex = 0; shotIndex < plan.shots().size(); shotIndex++) {
            var shot = plan.shots().get(shotIndex);
            Source video = require(sources, shot.videoVersionId());
            int videoIndex = inputIndex++;
            addInput(arguments, video.path());
            String shotDuration = decimal(shot.frames() / (double) VideoCompositionPlanner.FPS);
            int fadeMs = request.path("shots").get(shotIndex).path("fadeMs").asInt(0);
            if (fadeMs < 0 || fadeMs > 500 || fadeMs * 2 > shot.durationMs()) throw new IllegalArgumentException("Invalid shot fade");
            String fade = fadeMs == 0 ? "" : ",fade=t=in:st=0:d=" + seconds(fadeMs)
                    + ",fade=t=out:st=" + decimal(shot.frames() / (double) VideoCompositionPlanner.FPS - fadeMs / 1000.0)
                    + ":d=" + seconds(fadeMs);
            graph.add("[" + videoIndex + ":v:0]trim=start=" + seconds(shot.sourceStartMs()) + ":duration=" + seconds(shot.sourceDurationMs())
                    + ",setpts=(PTS-STARTPTS)/" + decimal(shot.speed()) + ",fps=30,scale=" + outputSettings.width() + ":" + outputSettings.height()
                    + ":force_original_aspect_ratio=decrease:force_divisible_by=2,pad=" + outputSettings.width() + ":" + outputSettings.height()
                    + ":(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p" + fade + "[v" + shotIndex + "]");
            if (keepOriginal && sourceHasAudio.get(shotIndex)) {
                String label = "original" + shotIndex;
                graph.add("[" + videoIndex + ":a:0]atrim=start=" + seconds(shot.sourceStartMs()) + ":duration=" + seconds(shot.sourceDurationMs())
                        + ",asetpts=PTS-STARTPTS,atempo=" + decimal(shot.speed()) + ",aresample=48000,aformat=channel_layouts=stereo,volume="
                        + (background ? "0.15" : "1") + ",atrim=duration=" + shotDuration + ",adelay="
                        + shot.timelineStartMs() + ":all=1[" + label + "]");
                mixed.add("[" + label + "]");
            }
            ObjectNode shotResult = shotResults.addObject().put("videoVersionId", shot.videoVersionId().toString())
                    .put("sourceStartMs", shot.sourceStartMs()).put("durationMs", shot.durationMs())
                    .put("sourceDurationMs", shot.sourceDurationMs()).put("speed", shot.speed()).put("fadeMs", fadeMs)
                    .put("activityTimeBasis", "OUTPUT_SHOT").put("timelineStartMs", shot.timelineStartMs());
            shotResult.set("sourceActivityWindows", mapper.valueToTree(shot.sourceActivityWindows()));
            var lineResults = shotResult.putArray("lines");
            int previousEnd = 0;
            for (int index = 0; index < shot.lines().size(); index++) {
                var line = shot.lines().get(index);
                Path processed = scratch.resolve("dialogue-" + lineIndex + ".wav");
                List<String> voiceArgs = arguments();
                addInput(voiceArgs, require(sources, line.audioVersionId()).path());
                voiceArgs.addAll(List.of("-map", "0:a:0", "-vn", "-af", "atrim=start=" + seconds(line.trimStartMs())
                        + ":end=" + seconds(line.trimEndMs()) + ",asetpts=PTS-STARTPTS,atempo=" + decimal(line.speed())
                        + ",aresample=48000,aformat=channel_layouts=stereo", "-c:a", "pcm_s16le", "-map_metadata", "-1", "-y", processed.toString()));
                budget.run(voiceArgs);
                int actualDuration = durationMs(processed);
                int actualEnd = Math.addExact(line.startMs(), actualDuration);
                int nextStart = Math.min(line.limitMs(), index + 1 == shot.lines().size()
                        ? shot.durationMs() : shot.lines().get(index + 1).startMs());
                // atempo may differ slightly from the duration estimate. Validate complete decoded audio;
                // never fix overflow by truncating speech or by using -shortest.
                if (line.startMs() < previousEnd || actualEnd > nextStart) throw new VideoCompositionPlanner.DoesNotFit();
                previousEnd = actualEnd;
                lastDialogueEndMs = Math.max(lastDialogueEndMs, shot.timelineStartMs() + actualEnd);
                int audioIndex = inputIndex++;
                addInput(arguments, processed);
                String label = "dialogue" + lineIndex++;
                graph.add("[" + audioIndex + ":a:0]asetpts=PTS-STARTPTS,adelay="
                        + (shot.timelineStartMs() + line.startMs()) + ":all=1[" + label + "]");
                mixed.add("[" + label + "]");
                lineResults.addObject().put("audioVersionId", line.audioVersionId().toString()).put("startMs", line.startMs())
                        .put("endMs", actualEnd).put("trimStartMs", line.trimStartMs()).put("trimEndMs", line.trimEndMs())
                        .put("speed", line.speed()).put("alignmentEvidence", line.alignmentEvidence())
                        .put("placementLimitMs", line.limitMs());
            }
        }
        JsonNode music = request.path("music");
        if (music.isObject()) {
            UUID musicId = UUID.fromString(music.path("audioVersionId").asText());
            Source musicSource = require(sources, musicId);
            int start = music.path("sourceStartMs").asInt(0);
            double volume = music.path("volume").asDouble(0.2);
            if (start < 0 || start >= musicSource.durationMs() || !Double.isFinite(volume) || volume < 0 || volume > 1)
                throw new IllegalArgumentException("Invalid music range or volume");
            int musicIndex = inputIndex++;
            addInput(arguments, musicSource.path());
            graph.add("[" + musicIndex + ":a:0]atrim=start=" + seconds(start) + ":duration=" + duration
                    + ",asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo,volume=" + decimal(volume) + "[music]");
            mixed.add("[music]");
            schedule.putObject("music").put("audioVersionId", musicId.toString()).put("sourceStartMs", start).put("volume", volume);
        }
        JsonNode subtitles = request.path("subtitles");
        boolean hasSubtitles = subtitles.isArray() && !subtitles.isEmpty();
        if (hasSubtitles) {
            CompositionSubtitles.write(subtitles, plan.durationMs(), outputSettings.width(), outputSettings.height(), properties.fontFamily(), scratch);
            schedule.set("subtitles", subtitles.deepCopy());
            schedule.put("subtitleFontFamily", properties.fontFamily());
        } else schedule.putArray("subtitles");
        StringBuilder concat = new StringBuilder();
        for (int index = 0; index < plan.shots().size(); index++) concat.append("[v").append(index).append(']');
        graph.add(concat + "concat=n=" + plan.shots().size() + ":v=1:a=0[" + (hasSubtitles ? "unsubtitled" : "video") + "]");
        if (hasSubtitles) graph.add("[unsubtitled]ass=filename=" + CompositionSubtitles.FILE_NAME + "[video]");
        graph.add(String.join("", mixed) + "amix=inputs=" + mixed.size()
                + ":duration=first:normalize=0,alimiter=limit=0.95:level=0:latency=1[audio]");
        Path output = scratch.resolve("composition.mp4");
        arguments.addAll(List.of("-filter_complex", String.join(";", graph), "-map", "[video]", "-map", "[audio]",
                "-frames:v", Integer.toString(plan.frames()), "-t", duration, "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-pix_fmt", "yuv420p", "-threads", "2", "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
                "-map_metadata", "-1", "-map_chapters", "-1", "-movflags", "+faststart", "-y", output.toString()));
        budget.run(arguments);
        JsonNode probe = probe(output);
        double actualSeconds = probe.at("/format/duration").asDouble(-1);
        JsonNode videoStream = probe.path("streams").valueStream()
                .filter(stream -> "video".equals(stream.path("codec_type").asText())).findFirst().orElseThrow();
        JsonNode audioStream = probe.path("streams").valueStream()
                .filter(stream -> "audio".equals(stream.path("codec_type").asText())).findFirst().orElseThrow();
        double audioSeconds = audioStream.path("duration").asDouble(-1);
        // Container duration may follow a longer source audio stream. If the decoded picture ends
        // early, FFmpeg can finish the mux early too. A duration tolerance must not hide lost frames
        // or a clipped final voice: verify the produced frame count and retained audio endpoint.
        if (videoStream.path("nb_frames").asInt(-1) != plan.frames() || !Double.isFinite(audioSeconds)
                || Math.floor(audioSeconds * 1000 + 0.000001) < lastDialogueEndMs)
            throw new IllegalStateException("Composition ended before the complete media timeline");
        if (!Double.isFinite(actualSeconds) || actualSeconds <= 0 || actualSeconds > 60
                || Math.abs(actualSeconds - plan.frames() / 30.0) > 0.05)
            throw new IllegalStateException("Composition duration differs from bounded timeline");
        schedule.put("durationMs", Math.round(actualSeconds * 1000));
        budget.check();
        return new Result(output, schedule);
    }

    private record Prepared(VideoCompositionPlanner.Plan plan, List<Boolean> hasAudio) {}
    private Prepared prepare(JsonNode request, Map<UUID, Source> sources, Path scratch, Budget budget) throws IOException {
        List<VideoCompositionPlanner.Shot> requested = new ArrayList<>();
        Map<UUID, AudioActivityAnalyzer.Analysis> audioAnalysis = new HashMap<>();
        List<Boolean> sourceHasAudio = new ArrayList<>();
        int sequence = 0;
        for (JsonNode shot : request.path("shots")) {
            UUID video = UUID.fromString(shot.path("videoVersionId").asText());
            Source source = require(sources, video);
            int start = shot.path("sourceStartMs").asInt(0), duration = shot.path("durationMs").asInt();
            if (start < 0 || duration <= 0 || (long) start + duration > source.durationMs())
                throw new IllegalArgumentException("VIDEO_COMPOSITION_INVALID");
            budget.check();
            boolean hasAudio = hasAudio(source.path());
            sourceHasAudio.add(hasAudio);
            AudioActivityAnalyzer.Analysis activity = hasAudio && !shot.path("lines").isEmpty()
                    ? analyze(source.path(), start, duration, true, scratch.resolve("source-" + sequence++ + ".pcm"), budget) : null;
            List<VideoCompositionPlanner.Line> lines = new ArrayList<>();
            for (JsonNode line : shot.path("lines")) {
                UUID audio = UUID.fromString(line.path("audioVersionId").asText());
                AudioActivityAnalyzer.Analysis analyzed = audioAnalysis.get(audio);
                if (analyzed == null) {
                    Source voice = require(sources, audio);
                    analyzed = analyze(voice.path(), 0, voice.durationMs(), false,
                            scratch.resolve("voice-" + sequence++ + ".pcm"), budget);
                    audioAnalysis.put(audio, analyzed);
                }
                Integer anchor = line.hasNonNull("startMs") ? line.path("startMs").intValue() : null;
                lines.add(new VideoCompositionPlanner.Line(audio, anchor, analyzed));
            }
            requested.add(new VideoCompositionPlanner.Shot(video, start, duration, shot.path("speed").asDouble(1), activity, List.copyOf(lines)));
        }
        var plan = planner.plan(requested);
        return new Prepared(plan, List.copyOf(sourceHasAudio));
    }

    JsonNode preview(JsonNode request, Map<UUID, Source> sources, Path scratch) throws IOException {
        var plan = prepare(request, sources, scratch, new Budget(scratch, () -> Thread.currentThread().isInterrupted(), () -> {})).plan();
        ObjectNode response = mapper.createObjectNode().put("durationMs", plan.durationMs()).put("frames", plan.frames())
                .put("fontFamily", properties.fontFamily());
        var shots = response.putArray("shots");
        for (var shot : plan.shots()) {
            var output = shots.addObject().put("videoVersionId", shot.videoVersionId().toString())
                    .put("timelineStartMs", shot.timelineStartMs()).put("durationMs", shot.durationMs());
            var lines = output.putArray("lines");
            for (var line : shot.lines()) lines.addObject().put("audioVersionId", line.audioVersionId().toString())
                    .put("startMs", line.startMs()).put("endMs", line.endMs()).put("trimStartMs", line.trimStartMs())
                    .put("trimEndMs", line.trimEndMs()).put("speed", line.speed());
        }
        return response;
    }

    private record OutputSettings(String resolution, String aspectRatio, int width, int height) {}

    private OutputSettings outputSettings(JsonNode output) {
        String resolution = output.path("resolution").asText("720P"), ratio = output.path("aspectRatio").asText("16:9");
        if (!Set.of("720P", "1080P").contains(resolution) || !Set.of("16:9", "9:16", "1:1").contains(ratio))
            throw new IllegalArgumentException("Invalid composition output");
        int edge = resolution.equals("720P") ? 720 : 1080, wide = edge * 16 / 9;
        return new OutputSettings(resolution, ratio, ratio.equals("16:9") ? wide : edge, ratio.equals("9:16") ? wide : edge);
    }

    private AudioActivityAnalyzer.Analysis analyze(Path path, int startMs, int durationMs, boolean sourceVideo,
            Path pcm, Budget budget) throws IOException {
        List<String> arguments = arguments();
        addInput(arguments, path);
        String selection = sourceVideo ? "atrim=start=" + seconds(startMs) + ":duration=" + seconds(durationMs) + "," : "";
        // Voice metadata is rounded to milliseconds; decode the full stream before exterior-silence
        // analysis so that the metadata value never becomes a speech-truncation boundary.
        arguments.addAll(List.of("-map", "0:a:0", "-vn", "-af", selection + "asetpts=PTS-STARTPTS", "-ac", "1", "-ar", Integer.toString(AudioActivityAnalyzer.SAMPLE_RATE),
                "-c:a", "pcm_s16le", "-f", "s16le", "-y", pcm.toString()));
        try {
            budget.run(arguments);
            if (Files.size(pcm) > 2L * AudioActivityAnalyzer.SAMPLE_RATE * 600)
                throw new IllegalArgumentException("Audio analysis size exceeded");
            if (sourceVideo && Files.size(pcm) == 0)
                return new AudioActivityAnalyzer.Analysis(durationMs, 0, durationMs, List.of(), false);
            return analyzer.analyze(Files.readAllBytes(pcm), sourceVideo);
        } finally { Files.deleteIfExists(pcm); }
    }
    private Source require(Map<UUID, Source> sources, UUID id) {
        Source source = sources.get(id);
        if (source == null || !source.path().isAbsolute() || source.durationMs() < 1)
            throw new IllegalArgumentException("Unresolved composition source");
        return source;
    }
    private boolean hasAudio(Path path) {
        return probe(path).path("streams").valueStream().anyMatch(s -> "audio".equals(s.path("codec_type").asText()));
    }
    private int durationMs(Path path) {
        double seconds = probe(path).at("/format/duration").asDouble(-1);
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 600) throw new IllegalArgumentException("Invalid audio duration");
        return (int) Math.ceil(seconds * 1000);
    }
    private JsonNode probe(Path path) {
        return mapper.readTree(tools.ffprobe(List.of("-v", "error", "-protocol_whitelist", "file,pipe",
                "-show_entries", "stream=codec_type,duration,nb_frames:format=duration", "-of", "json", path.toString())));
    }
    private static List<String> arguments() {
        return new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-threads", "2", "-filter_threads", "2", "-filter_complex_threads", "2"));
    }
    private static void addInput(List<String> arguments, Path path) {
        arguments.addAll(List.of("-protocol_whitelist", "file,pipe", "-i", path.toString()));
    }
    private static String seconds(int millis) { return decimal(millis / 1000.0); }
    private static String decimal(double value) { return String.format(Locale.ROOT, "%.6f", value); }

    private final class Budget {
        private final Path scratch;
        private final BooleanSupplier canceled;
        private final Runnable heartbeat;
        private final long deadline = System.nanoTime() + TIMEOUT.toNanos();
        Budget(Path scratch, BooleanSupplier canceled, Runnable heartbeat) {
            this.scratch = scratch; this.canceled = canceled; this.heartbeat = heartbeat;
        }
        void check() {
            if (canceled.getAsBoolean()) throw new CancellationException();
            if (System.nanoTime() >= deadline) throw new MediaToolRunner.MediaToolException("Composition timed out", false, null);
            heartbeat.run();
            try (var paths = Files.list(scratch)) {
                long bytes = 0;
                for (Path path : paths.toList()) if (Files.isRegularFile(path)) bytes = Math.addExact(bytes, Files.size(path));
                if (bytes > MAX_SCRATCH_BYTES) throw new IllegalStateException("Composition scratch bound exceeded");
            } catch (IOException failure) { throw new IllegalStateException("Cannot inspect private composition scratch", failure); }
        }
        void run(List<String> arguments) {
            check();
            Duration remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
            tools.ffmpegExport(arguments, scratch, remaining, canceled, this::check);
            check();
        }
    }
}
