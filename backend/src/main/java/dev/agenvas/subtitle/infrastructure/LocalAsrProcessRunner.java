package dev.agenvas.subtitle.infrastructure;

import dev.agenvas.subtitle.application.LocalAsrException;
import dev.agenvas.subtitle.application.LocalAsrProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/** Fixed local executable, data-only arguments, no inherited credentials and bounded private output. */
@Component
public class LocalAsrProcessRunner {
    public static final long MAX_SCRATCH_BYTES = 64L * 1024 * 1024;

    /** Heartbeat exceptions propagate to the lease owner; cancellation is never converted to success. */
    public void run(Path executable, List<String> arguments, Path scratch, Duration timeout,
            BooleanSupplier canceled, Runnable heartbeat) {
        if (executable == null || !executable.isAbsolute() || !Files.isExecutable(executable)
                || scratch == null || !scratch.isAbsolute() || !Files.isDirectory(scratch, LinkOption.NOFOLLOW_LINKS)
                || timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(LocalAsrProperties.MAX_TIMEOUT) > 0 || canceled == null || heartbeat == null)
            throw new IllegalArgumentException("Invalid local ASR process settings");
        var command = new ArrayList<String>();
        command.add(executable.toString());
        command.addAll(List.copyOf(arguments));
        var builder = new ProcessBuilder(command).directory(scratch.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        builder.environment().put("TMPDIR", scratch.toString());
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process = null;
        try {
            check(scratch, deadline, canceled);
            heartbeat.run();
            process = builder.start();
            long nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                check(scratch, deadline, canceled);
                if (System.nanoTime() >= nextHeartbeat) {
                    heartbeat.run();
                    nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                }
            }
            // A very fast native process can finish between polls, including after cancellation.
            check(scratch, deadline, canceled);
            if (process.exitValue() != 0) throw new LocalAsrException(LocalAsrException.Code.FAILED);
            heartbeat.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Local ASR interrupted");
        } catch (IOException failure) {
            throw new LocalAsrException(LocalAsrException.Code.FAILED);
        } finally {
            if (process != null && process.isAlive()) {
                // A configured wrapper may launch a child engine. Stop children as well as the
                // wrapper so a canceled job cannot continue consuming the instance's resources.
                try {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                } catch (RuntimeException unsupported) {
                    // Some restricted macOS runtimes forbid the sysctl used for enumeration.
                    // This must never prevent termination of the native CLI itself or mask the
                    // original timeout/cancellation. Configure the native binary, not a wrapper.
                    org.slf4j.LoggerFactory.getLogger(LocalAsrProcessRunner.class)
                            .debug("Local ASR descendant enumeration unavailable");
                } finally { process.destroyForcibly(); }
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) throw new LocalAsrException(LocalAsrException.Code.FAILED);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Shared by decoding and recognition, so each poll enforces the same disk and cancel boundary. */
    public static void check(Path scratch, long deadline, BooleanSupplier canceled) {
        if (Thread.currentThread().isInterrupted() || canceled.getAsBoolean()) throw new CancellationException("Local ASR canceled");
        if (System.nanoTime() >= deadline) throw new LocalAsrException(LocalAsrException.Code.TIMED_OUT);
        try (var paths = Files.walk(scratch)) {
            long bytes = 0;
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (Files.isSymbolicLink(file)) throw new LocalAsrException(LocalAsrException.Code.INVALID_OUTPUT);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) bytes = Math.addExact(bytes, Files.size(file));
                if (bytes > MAX_SCRATCH_BYTES) throw new LocalAsrException(LocalAsrException.Code.SCRATCH_LIMIT);
            }
        } catch (IOException | ArithmeticException failure) {
            throw new LocalAsrException(LocalAsrException.Code.FAILED);
        }
    }
}
