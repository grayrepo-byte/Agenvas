package dev.agenvas.asset.application;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operator-owned fixed media binaries; no model or request can supply a command path. */
@ConfigurationProperties(prefix = "agenvas.media.tools")
public record MediaToolsProperties(Path ffmpeg, Path ffprobe, Duration timeout) {

    /** Defaults are for the Alpine runtime image; tests override them for the host. */
    public MediaToolsProperties {
        ffmpeg = ffmpeg == null ? defaultBinary("ffmpeg") : ffmpeg;
        ffprobe = ffprobe == null ? defaultBinary("ffprobe") : ffprobe;
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        if (!ffmpeg.isAbsolute() || !ffprobe.isAbsolute()
                || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(20)) > 0) {
            throw new IllegalArgumentException("Media binaries must be absolute and timeout bounded");
        }
    }

    /** Resolves one of three fixed local installation paths once at application startup. */
    private static Path defaultBinary(String name) {
        for (String directory : new String[] {"/usr/bin", "/opt/homebrew/bin", "/usr/local/bin"}) {
            Path candidate = Path.of(directory, name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return Path.of("/usr/bin", name);
    }
}
