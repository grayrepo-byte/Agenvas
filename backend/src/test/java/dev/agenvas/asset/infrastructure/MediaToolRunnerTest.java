package dev.agenvas.asset.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.asset.application.MediaToolsProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Subprocess fault tests use fixed local binaries, never a shell command from a request. */
class MediaToolRunnerTest {

    @Test
    void exportCancellationAndTimeoutTerminateTheChild() {
        Path sleep = binary("sleep");
        MediaToolRunner runner = new MediaToolRunner(
                new MediaToolsProperties(sleep, sleep, Duration.ofSeconds(20)));
        AtomicBoolean canceled = new AtomicBoolean();
        long started = System.nanoTime();
        assertThatThrownBy(() -> runner.ffmpegExport(List.of("20"),
                Duration.ofSeconds(10), canceled::get, () -> canceled.set(true)))
                .isInstanceOf(CancellationException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(8));

        started = System.nanoTime();
        assertThatThrownBy(() -> runner.ffmpegExport(List.of("20"),
                Duration.ofMillis(50), () -> false, () -> {}))
                .isInstanceOfSatisfying(MediaToolRunner.MediaToolException.class,
                        failure -> assertThat(failure.invalidInput()).isFalse());
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(6));
    }

    @Test
    void exportNonzeroExitDoesNotPretendThatPinnedMediaWasInvalid() {
        Path failureBinary = binary("false");
        MediaToolRunner runner = new MediaToolRunner(new MediaToolsProperties(
                failureBinary, failureBinary, Duration.ofSeconds(20)));
        assertThatThrownBy(() -> runner.ffmpegExport(List.of(),
                Duration.ofSeconds(3), () -> false, () -> {}))
                .isInstanceOfSatisfying(MediaToolRunner.MediaToolException.class,
                        failure -> assertThat(failure.invalidInput()).isFalse());
    }

    private Path binary(String name) {
        for (String directory : List.of("/bin", "/usr/bin")) {
            Path path = Path.of(directory, name);
            if (Files.isExecutable(path)) return path;
        }
        throw new IllegalStateException("Required test binary is unavailable: " + name);
    }
}
