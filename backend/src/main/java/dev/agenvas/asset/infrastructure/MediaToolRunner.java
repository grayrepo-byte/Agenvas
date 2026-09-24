package dev.agenvas.asset.infrastructure;

import dev.agenvas.asset.application.MediaToolsProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/** Executes only server-built argument arrays with bounded time and discarded diagnostics. */
@Component
public class MediaToolRunner {

    /** Distinguishes bad media from an unavailable or interrupted local tool. */
    public static final class MediaToolException extends IllegalStateException {
        private final boolean invalidInput;

        public MediaToolException(String message, boolean invalidInput, Throwable cause) {
            super(message, cause);
            this.invalidInput = invalidInput;
        }

        public boolean invalidInput() {
            return invalidInput;
        }
    }

    private final MediaToolsProperties properties;
    private final Path defaultWorkDirectory;

    public MediaToolRunner(MediaToolsProperties properties) {
        this.properties = properties;
        defaultWorkDirectory = Path.of(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(defaultWorkDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Media tool work directory must exist");
        }
    }

    /** Runs a fixed ffmpeg operation without a shell or inherited environment output. */
    public void ffmpeg(List<String> arguments) {
        execute(properties.ffmpeg(), arguments, null, null, properties.timeout(), null, null, true);
    }

    /** Long local exports run inside their private scratch directory and poll cancellation. */
    public void ffmpegExport(List<String> arguments, Path workDirectory, Duration timeout,
            BooleanSupplier shouldCancel, Runnable onTick) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(3)) > 0
                || workDirectory == null || !workDirectory.isAbsolute()
                || !Files.isDirectory(workDirectory, LinkOption.NOFOLLOW_LINKS)
                || shouldCancel == null || onTick == null) {
            throw new IllegalArgumentException("Invalid bounded export process settings");
        }
        execute(properties.ffmpeg(), arguments, workDirectory, null, timeout,
                shouldCancel, onTick, false);
    }

    /** Runs ffprobe into a bounded local result file, never accepting a remote URL. */
    public String ffprobe(List<String> arguments) {
        Path output;
        try {
            output = Files.createTempFile("agenvas-probe-", ".json");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create media probe output", exception);
        }
        try {
            execute(properties.ffprobe(), arguments, null, output, properties.timeout(), null, null,
                    true);
            if (Files.size(output) > 16_384) {
                throw new IllegalStateException("Media probe output exceeded the limit");
            }
            return Files.readString(output);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read media probe output", exception);
        } finally {
            try {
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
                // An unreferenced probe file is never an archived asset.
            }
        }
    }

    private void execute(Path executable, List<String> arguments, Path workDirectory, Path output,
            Duration timeout, BooleanSupplier shouldCancel, Runnable onTick,
            boolean nonzeroIsInvalidInput) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(executable.toString());
        command.addAll(arguments);
        Path selectedDirectory = workDirectory == null ? defaultWorkDirectory : workDirectory;
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(selectedDirectory.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(output == null ? ProcessBuilder.Redirect.DISCARD
                        : ProcessBuilder.Redirect.to(output.toFile()));
        // Media decoders must not inherit bootstrap, database or provider credentials.
        builder.environment().clear();
        builder.environment().put("TMPDIR", selectedDirectory.toString());
        Process process = null;
        try {
            process = builder.start();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                if (shouldCancel != null && shouldCancel.getAsBoolean()) {
                    throw new CancellationException("Media export canceled");
                }
                if (onTick != null) {
                    onTick.run();
                }
                if (System.nanoTime() >= deadline) {
                    throw new MediaToolException("Media tool timed out", false, null);
                }
            }
            if (process.exitValue() != 0) {
                throw new MediaToolException("Media tool failed with exit "
                        + process.exitValue(), nonzeroIsInvalidInput, null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaToolException("Media tool interrupted", false, exception);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new MediaToolException("Media tool could not start", false, exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        throw new MediaToolException("Media tool did not stop after termination",
                                false, null);
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new MediaToolException("Media tool termination interrupted",
                            false, failure);
                }
            }
        }
    }
}
