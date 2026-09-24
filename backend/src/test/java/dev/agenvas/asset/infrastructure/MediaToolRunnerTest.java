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
import org.junit.jupiter.api.io.TempDir;

/** Subprocess fault tests use fixed local binaries, never a shell command from a request. */
class MediaToolRunnerTest {

    @TempDir Path workDirectory;

    @Test
    void exportCancellationAndTimeoutTerminateTheChild() {
        Path sleep = binary("sleep");
        MediaToolRunner runner = new MediaToolRunner(
                new MediaToolsProperties(sleep, sleep, Duration.ofSeconds(20)));
        AtomicBoolean canceled = new AtomicBoolean();
        long started = System.nanoTime();
        assertThatThrownBy(() -> runner.ffmpegExport(List.of("20"), workDirectory,
                Duration.ofSeconds(10), canceled::get, () -> canceled.set(true)))
                .isInstanceOf(CancellationException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(8));

        started = System.nanoTime();
        assertThatThrownBy(() -> runner.ffmpegExport(List.of("20"), workDirectory,
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
        assertThatThrownBy(() -> runner.ffmpegExport(List.of(), workDirectory,
                Duration.ofSeconds(3), () -> false, () -> {}))
                .isInstanceOfSatisfying(MediaToolRunner.MediaToolException.class,
                        failure -> assertThat(failure.invalidInput()).isFalse());
    }

    /** The actual child sees only the server-selected private work directory. */
    @Test
    void exportUsesExplicitWorkDirectoryAndRejectsSymlink() throws Exception {
        Path touch = binary("touch");
        MediaToolRunner runner = new MediaToolRunner(new MediaToolsProperties(
                touch, touch, Duration.ofSeconds(20)));
        runner.ffmpegExport(List.of("created-by-child"), workDirectory,
                Duration.ofSeconds(3), () -> false, () -> {});
        assertThat(Files.isRegularFile(workDirectory.resolve("created-by-child"))).isTrue();

        Path symlink = workDirectory.resolve("linked-directory");
        Files.createSymbolicLink(symlink, workDirectory);
        assertThatThrownBy(() -> runner.ffmpegExport(List.of("must-not-exist"), symlink,
                Duration.ofSeconds(3), () -> false, () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.ffmpegExport(List.of("must-not-exist"),
                Path.of("relative-work-directory"), Duration.ofSeconds(3),
                () -> false, () -> {})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.ffmpegExport(List.of("must-not-exist"),
                workDirectory.resolve("missing-directory"), Duration.ofSeconds(3),
                () -> false, () -> {})).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(workDirectory.resolve("must-not-exist"))).isFalse();
    }

    /** Fixed subprocesses cannot read the application's inherited environment. */
    @Test
    void mediaSubprocessDoesNotInheritApplicationEnvironment() {
        Path printenv = binary("printenv");
        MediaToolRunner runner = new MediaToolRunner(new MediaToolsProperties(
                printenv, printenv, Duration.ofSeconds(20)));
        assertThatThrownBy(() -> runner.ffmpegExport(List.of("PATH"), workDirectory,
                Duration.ofSeconds(3), () -> false, () -> {}))
                .isInstanceOf(MediaToolRunner.MediaToolException.class);
        assertThat(System.getenv("PATH")).isNotBlank();
    }

    private Path binary(String name) {
        for (String directory : List.of("/bin", "/usr/bin")) {
            Path path = Path.of(directory, name);
            if (Files.isExecutable(path)) return path;
        }
        throw new IllegalStateException("Required test binary is unavailable: " + name);
    }
}
