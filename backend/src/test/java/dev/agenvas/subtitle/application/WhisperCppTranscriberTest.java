package dev.agenvas.subtitle.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.asset.application.MediaToolsProperties;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.subtitle.infrastructure.LocalAsrProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhisperCppTranscriberTest {
    @Test
    void realWhisperFrameTailIsClampedButLargeOrEmptyIntervalsAreRejected() {
        var source = new WhisperCppTranscriber.Source(UUID.randomUUID(), Path.of("/tmp/test.wav"), 7998);
        var track = WhisperCppTranscriber.decodeRecognitionOutput("1\n00:00:00,000 --> 00:00:08,060\nHello\n", source,
                "en");
        assertThat(track.sourceDurationMs()).isEqualTo(7998);
        assertThat(track.cues().getFirst().endMs()).isEqualTo(7998);
        assertThat(WhisperCppTranscriber.decodeRecognitionOutput(
                "1\n00:00:00,000 --> 00:00:08,098\nHello\n", source, "en").cues().getFirst().endMs()).isEqualTo(7998);
        assertThatThrownBy(() -> WhisperCppTranscriber.decodeRecognitionOutput(
                "1\n00:00:00,000 --> 00:00:08,099\nHello\n", source, "en"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WhisperCppTranscriber.decodeRecognitionOutput(
                "1\n00:00:00,000 --> 00:00:08,200\nHello\n", source, "en"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WhisperCppTranscriber.decodeRecognitionOutput(
                "1\n00:00:08,000 --> 00:00:08,060\nHello\n", source, "en"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @TempDir
    Path temp;

    @Test
    void absentConfigurationReturnsNotConfiguredWithoutTryingToRunOrDownloadAnything() {
        var transcriber = new WhisperCppTranscriber(new MediaToolRunner(new MediaToolsProperties(null, null, null)),
                new LocalAsrProperties(null, null, null, null), new LocalAsrProcessRunner());
        assertThat(transcriber.availability()).isEqualTo(WhisperCppTranscriber.Availability.NOT_CONFIGURED);
        assertThatThrownBy(() -> transcriber.transcribe(new WhisperCppTranscriber.Source(UUID.randomUUID(),
                temp.resolve("unused.wav"), 1000), "auto", () -> false, () -> {
                }))
                .isInstanceOfSatisfying(LocalAsrException.class,
                        failure -> assertThat(failure.code()).isEqualTo(LocalAsrException.Code.NOT_CONFIGURED));
    }

    @Test
    void sourceProbeSharesTheRecognitionDeadlineAndCancellation() throws Exception {
        // Exercise a real bounded child, with controlled probe arguments; no speech engine runs.
        Path sleep = Files.isExecutable(Path.of("/bin/sleep")) ? Path.of("/bin/sleep") : Path.of("/usr/bin/sleep");
        var tools = new MediaToolRunner(new MediaToolsProperties(null, sleep, Duration.ofSeconds(20))) {
            @Override
            public String ffprobeExport(List<String> args, Path scratch, Duration timeout,
                    BooleanSupplier canceled, Runnable heartbeat) {
                return super.ffprobeExport(List.of("20"), scratch, timeout, canceled, heartbeat);
            }
        };
        Path source = Files.writeString(temp.resolve("probe-fixture.wav"), "controlled process fixture, not audio");
        Path model = Files.writeString(temp.resolve("synthetic.bin"), "not a real model");
        var timed = new WhisperCppTranscriber(tools, new LocalAsrProperties(Path.of("/usr/bin/true"), model,
                Duration.ofMillis(100), null), new LocalAsrProcessRunner());
        var input = new WhisperCppTranscriber.Source(UUID.randomUUID(), source, 1000);
        assertThatThrownBy(() -> timed.transcribe(input, "auto", () -> false, () -> {
        }))
                .isInstanceOfSatisfying(LocalAsrException.class,
                        failure -> assertThat(failure.code()).isEqualTo(LocalAsrException.Code.TIMED_OUT));
        var canceled = new AtomicBoolean();
        var cancelable = new WhisperCppTranscriber(tools, new LocalAsrProperties(Path.of("/usr/bin/true"), model,
                Duration.ofSeconds(10), null), new LocalAsrProcessRunner());
        assertThatThrownBy(() -> cancelable.transcribe(input, "auto", canceled::get, () -> canceled.set(true)))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void protocolFixtureReceivesRealDecodedMonoPcmAndReturnsAPinnedCorrectableDraft() throws Exception {
        var tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));
        Path source = temp.resolve("audio ; [safe].wav");
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-ac", "2", "-ar", "44100", "-y", source.toString()));
        Path model = Files.writeString(temp.resolve("synthetic-not-a-model.bin"), "protocol fixture only");
        var work = new AtomicReference<Path>();
        var runner = new LocalAsrProcessRunner() {
            @Override
            public void run(Path executable, List<String> args, Path scratch, Duration timeout,
                    BooleanSupplier canceled, Runnable heartbeat) {
                work.set(scratch);
                assertThat(args).containsSubsequence("--language", "zh", "--threads", "2", "--processors", "1",
                        "--no-gpu");
                assertThat(args).doesNotContain("--translate", "--prompt");
                try (var audio = AudioSystem.getAudioInputStream(scratch.resolve("input.wav").toFile())) {
                    assertThat(audio.getFormat().getSampleRate()).isEqualTo(16000);
                    assertThat(audio.getFormat().getChannels()).isEqualTo(1);
                    assertThat(audio.getFormat().getSampleSizeInBits()).isEqualTo(16);
                    Files.writeString(scratch.resolve("transcript.srt"), "1\n00:00:00,100 --> 00:00:00,900\n合成协议夹具\n");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        };
        var transcriber = new WhisperCppTranscriber(tools,
                new LocalAsrProperties(Path.of("/usr/bin/true"), model, null, null), runner);
        UUID fixed = UUID.randomUUID();
        var result = transcriber.transcribe(new WhisperCppTranscriber.Source(fixed, source, 1000), "zh", () -> false,
                () -> {
                });
        assertThat(result.sourceVersionId()).isEqualTo(fixed);
        assertThat(result.sourceDurationMs()).isEqualTo(1000);
        assertThat(result.cues()).containsExactly(new dev.agenvas.subtitle.domain.SubtitleCue("合成协议夹具", 100, 900));
        assertThat(work.get()).doesNotExist();
        assertThat(source).exists();
    }

    @Test
    void rechecksAdmissionAfterDecodingAndDoesNotStartAnEngineWhenItIsDenied() throws Exception {
        var tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));
        Path source = temp.resolve("admission.wav");
        tools.ffmpeg(
                List.of("-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-y", source.toString()));
        Path model = Files.writeString(temp.resolve("fixture.bin"), "not a model");
        var launched = new AtomicBoolean();
        var runner = new LocalAsrProcessRunner() {
            @Override
            public void run(Path executable, List<String> args, Path scratch, Duration timeout,
                    BooleanSupplier canceled, Runnable heartbeat) {
                launched.set(true);
            }
        };
        var recognizer = new WhisperCppTranscriber(tools,
                new LocalAsrProperties(Path.of("/usr/bin/true"), model, null, null), runner);
        assertThatThrownBy(() -> recognizer.transcribe(
                new WhisperCppTranscriber.Source(UUID.randomUUID(), source, 1000), "auto", () -> false, () -> {
                },
                () -> {
                    throw new IllegalStateException("controlled admission refusal");
                }))
                .isInstanceOf(IllegalStateException.class).hasMessage("controlled admission refusal");
        assertThat(launched).isFalse();
    }

    @Test
    void delayedAudioUsesSourceVideoTimeAndAllowsAShorterAudioStream() throws Exception {
        var tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));
        Path video = temp.resolve("delayed-audio.mp4");
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "color=c=black:s=160x90:r=30:d=3",
                "-itsoffset", "1", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-y", video.toString()));
        Path model = Files.writeString(temp.resolve("synthetic.bin"), "protocol fixture only");
        var runner = new LocalAsrProcessRunner() {
            @Override
            public void run(Path executable, List<String> args, Path scratch, Duration timeout,
                    BooleanSupplier canceled, Runnable heartbeat) {
                try (var audio = AudioSystem.getAudioInputStream(scratch.resolve("input.wav").toFile())) {
                    byte[] prefix = audio.readNBytes(16000); // First 500ms must retain the source's leading silence.
                    for (byte sample : prefix)
                        assertThat(sample).isZero();
                    Files.writeString(scratch.resolve("transcript.srt"), "1\n00:00:01,100 --> 00:00:01,900\n延迟音轨夹具\n");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        };
        var transcriber = new WhisperCppTranscriber(tools,
                new LocalAsrProperties(Path.of("/usr/bin/true"), model, null, null), runner);
        assertThat(transcriber.transcribe(new WhisperCppTranscriber.Source(UUID.randomUUID(), video, 3000),
                "zh", () -> false, () -> {
                }).cues()).containsExactly(
                        new dev.agenvas.subtitle.domain.SubtitleCue("延迟音轨夹具", 1100, 1900));
    }

    @Test
    void canceledLateProtocolOutputIsDiscardedAndPrivateFilesAreRemoved() throws Exception {
        var tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));
        Path audio = temp.resolve("source.wav");
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono", "-t", "1", "-y",
                audio.toString()));
        Path model = Files.writeString(temp.resolve("synthetic.bin"), "not a real model");
        var cancel = new AtomicBoolean();
        var work = new AtomicReference<Path>();
        var runner = new LocalAsrProcessRunner() {
            @Override
            public void run(Path executable, List<String> args, Path scratch, Duration timeout,
                    BooleanSupplier canceled, Runnable heartbeat) {
                work.set(scratch);
                try {
                    Files.writeString(scratch.resolve("transcript.srt"),
                            "1\n00:00:00,100 --> 00:00:00,900\nlate fixture\n");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                cancel.set(true);
            }
        };
        var transcriber = new WhisperCppTranscriber(tools,
                new LocalAsrProperties(Path.of("/usr/bin/true"), model, null, null), runner);
        assertThatThrownBy(
                () -> transcriber.transcribe(new WhisperCppTranscriber.Source(UUID.randomUUID(), audio, 1000),
                        "auto", cancel::get, () -> {
                        }))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(work.get()).doesNotExist();
    }

    @Test
    void boundsConcurrentModelLoadsAndReleasesTheSlotAfterCancellation() throws Exception {
        var tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));
        Path audio = temp.resolve("source.wav");
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono", "-t", "1", "-y",
                audio.toString()));
        Path model = Files.writeString(temp.resolve("synthetic.bin"), "not a real model");
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var cancel = new AtomicBoolean();
        var runner = new LocalAsrProcessRunner() {
            @Override
            public void run(Path executable, List<String> args, Path scratch, Duration timeout,
                    BooleanSupplier canceled, Runnable heartbeat) {
                entered.countDown();
                try {
                    assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
                    Files.writeString(scratch.resolve("transcript.srt"), "");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        };
        var transcriber = new WhisperCppTranscriber(tools,
                new LocalAsrProperties(Path.of("/usr/bin/true"), model, null, null), runner);
        var source = new WhisperCppTranscriber.Source(UUID.randomUUID(), audio, 1000);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> transcriber.transcribe(source, "auto", cancel::get, () -> {
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> transcriber.transcribe(source, "auto", () -> false, () -> {
                }))
                        .isInstanceOfSatisfying(LocalAsrException.class,
                                failure -> assertThat(failure.code()).isEqualTo(LocalAsrException.Code.BUSY));
                cancel.set(true);
            } finally {
                released.countDown();
            }
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
        }
        assertThat(transcriber.transcribe(source, "auto", () -> false, () -> {
        }).cues()).isEmpty();
    }

    @Test
    void videoWithoutAnAudioStreamReturnsNoAudioRatherThanAnInventedTranscript() throws Exception {
        var tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));
        Path video = temp.resolve("no-audio.mp4");
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "color=c=black:s=160x90:r=30:d=1",
                "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", video.toString()));
        Path model = Files.writeString(temp.resolve("synthetic.bin"), "not a real model");
        var transcriber = new WhisperCppTranscriber(tools,
                new LocalAsrProperties(Path.of("/usr/bin/true"), model, null, null),
                new LocalAsrProcessRunner());
        assertThatThrownBy(
                () -> transcriber.transcribe(new WhisperCppTranscriber.Source(UUID.randomUUID(), video, 1000),
                        "auto", () -> false, () -> {
                        }))
                .isInstanceOfSatisfying(LocalAsrException.class,
                        failure -> assertThat(failure.code()).isEqualTo(LocalAsrException.Code.NO_AUDIO));
    }

    @Test
    void missingOrMalformedNativeOutputFailsInsteadOfReturningAPartialDraft() throws Exception {
        var tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));
        Path audio = temp.resolve("source.wav");
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono", "-t", "1", "-y",
                audio.toString()));
        Path model = Files.writeString(temp.resolve("synthetic.bin"), "not a real model");
        var source = new WhisperCppTranscriber.Source(UUID.randomUUID(), audio, 1000);
        var missing = new WhisperCppTranscriber(tools,
                new LocalAsrProperties(Path.of("/usr/bin/true"), model, null, null),
                new LocalAsrProcessRunner());
        assertThatThrownBy(() -> missing.transcribe(source, "auto", () -> false, () -> {
        }))
                .isInstanceOfSatisfying(LocalAsrException.class,
                        failure -> assertThat(failure.code()).isEqualTo(LocalAsrException.Code.INVALID_OUTPUT));
        var malformed = new WhisperCppTranscriber(tools,
                new LocalAsrProperties(Path.of("/usr/bin/true"), model, null, null),
                new LocalAsrProcessRunner() {
                    @Override
                    public void run(Path executable, List<String> args, Path scratch, Duration timeout,
                            BooleanSupplier canceled, Runnable heartbeat) {
                        try {
                            Files.writeString(scratch.resolve("transcript.srt"),
                                    "1\n00:00:00,100 --> 00:00:01,200\noutside source\n");
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    }
                });
        assertThatThrownBy(() -> malformed.transcribe(source, "auto", () -> false, () -> {
        }))
                .isInstanceOfSatisfying(LocalAsrException.class,
                        failure -> assertThat(failure.code()).isEqualTo(LocalAsrException.Code.INVALID_OUTPUT));
    }
}
