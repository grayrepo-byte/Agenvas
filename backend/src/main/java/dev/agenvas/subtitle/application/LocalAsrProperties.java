package dev.agenvas.subtitle.application;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operator-owned local whisper.cpp installation. Null paths keep recognition unconfigured. */
@ConfigurationProperties(prefix = "agenvas.subtitles.local-asr")
public record LocalAsrProperties(Path executable, Path model, Duration timeout, Integer threads) {
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);
    public static final int MAX_SOURCE_DURATION_MS = 600_000;

    public LocalAsrProperties {
        timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
        threads = threads == null ? 2 : threads;
        if ((executable != null && !executable.isAbsolute()) || (model != null && !model.isAbsolute())
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0
                || threads < 1 || threads > 4)
            throw new IllegalArgumentException("Invalid bounded local ASR configuration");
    }
}
