package dev.agenvas.provider.application;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fixed server-side model path; requests can never choose or load an arbitrary model file. */
@ConfigurationProperties(prefix = "agenvas.provider.local-image")
public record LocalImageProperties(String depthModel) {
    public Path depthModelPath() {
        return depthModel == null || depthModel.isBlank()
                ? null : Path.of(depthModel).toAbsolutePath().normalize();
    }
}
