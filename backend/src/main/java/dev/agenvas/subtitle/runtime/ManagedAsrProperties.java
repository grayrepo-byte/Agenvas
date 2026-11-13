package dev.agenvas.subtitle.runtime;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Only deployment configuration can select a writable persistent model volume. */
@ConfigurationProperties("agenvas.subtitles.managed")
public record ManagedAsrProperties(Path root, Mode mode) {
    public enum Mode {
        AUTO, MANAGED, MANUAL
    }
    public ManagedAsrProperties {
        root = root == null ? Path.of("/opt/agenvas/asr") : root;
        if (!root.isAbsolute())
            throw new IllegalArgumentException("ASR model root must be absolute");
        root = root.normalize();
        mode = mode == null ? Mode.AUTO : mode;
    }
}
