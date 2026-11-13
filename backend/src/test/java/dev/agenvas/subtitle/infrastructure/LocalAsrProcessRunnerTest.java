package dev.agenvas.subtitle.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.subtitle.application.LocalAsrException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Controlled native processes test lifecycle and isolation, not speech recognition quality. */
class LocalAsrProcessRunnerTest {
    @TempDir
    Path temp;
    private final LocalAsrProcessRunner runner = new LocalAsrProcessRunner();

    @Test
    void enforcesTimeoutAndCancellationWhileTheNativeProcessIsStillRunning() {
        assertThatThrownBy(
                () -> runner.run(Path.of("/bin/sleep"), List.of("5"), temp, Duration.ofMillis(100), () -> false, () -> {
                }))
                .isInstanceOfSatisfying(LocalAsrException.class,
                        failure -> assertThat(failure.code()).isEqualTo(LocalAsrException.Code.TIMED_OUT));
        var checks = new AtomicInteger();
        assertThatThrownBy(() -> runner.run(Path.of("/bin/sleep"), List.of("5"), temp, Duration.ofSeconds(2),
                () -> checks.incrementAndGet() > 1, () -> {
                })).isInstanceOf(CancellationException.class);
    }

    @Test
    void clearsInheritedEnvironmentAndSuppressesNativeErrorOutput() throws Exception {
        Path executable = script(
                "#!/bin/sh\n/usr/bin/env > process-environment\necho 'PRIVATE_NATIVE_ERROR' >&2\nexit 7\n");
        assertThatThrownBy(() -> runner.run(executable, List.of(), temp, Duration.ofSeconds(2), () -> false, () -> {
        }))
                .isInstanceOfSatisfying(LocalAsrException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LocalAsrException.Code.FAILED);
                    assertThat(failure.getMessage()).doesNotContain("PRIVATE_NATIVE_ERROR", temp.toString());
                });
        assertThat(Files.readString(temp.resolve("process-environment"))).contains("TMPDIR=" + temp)
                .doesNotContain("HOME=", "USER=", "JAVA_HOME=", "OPENAI_API_KEY=");
    }

    @Test
    void checksScratchBudgetAndSymlinksBeforeAndAfterAQuickNativeProcess() throws Exception {
        Path oversized = temp.resolve("oversized");
        try (var file = new RandomAccessFile(oversized.toFile(), "rw")) {
            file.setLength(LocalAsrProcessRunner.MAX_SCRATCH_BYTES + 1);
        }
        assertThatThrownBy(
                () -> runner.run(Path.of("/usr/bin/true"), List.of(), temp, Duration.ofSeconds(2), () -> false, () -> {
                }))
                .isInstanceOfSatisfying(LocalAsrException.class,
                        failure -> assertThat(failure.code()).isEqualTo(LocalAsrException.Code.SCRATCH_LIMIT));
        Files.delete(oversized);
        Path executable = script("#!/bin/sh\n/bin/ln -s controlled-test-process linked-output\n");
        assertThatThrownBy(() -> runner.run(executable, List.of(), temp, Duration.ofSeconds(2), () -> false, () -> {
        }))
                .isInstanceOfSatisfying(LocalAsrException.class,
                        failure -> assertThat(failure.code()).isEqualTo(LocalAsrException.Code.INVALID_OUTPUT));
    }

    @Test
    void keepsTheThreadInterruptFlagAndPropagatesLeaseHeartbeatFailure() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> runner.run(Path.of("/usr/bin/true"), List.of(), temp, Duration.ofSeconds(2),
                    () -> false, () -> {
                    }))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        var leaseFailure = new IllegalStateException("synthetic lease lost");
        assertThatThrownBy(
                () -> runner.run(Path.of("/bin/sleep"), List.of("5"), temp, Duration.ofSeconds(2), () -> false,
                        () -> {
                            throw leaseFailure;
                        }))
                .isSameAs(leaseFailure);
    }

    private Path script(String contents) throws Exception {
        Path file = Files.writeString(temp.resolve("controlled-test-process"), contents);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }
}
