package dev.agenvas.identity.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated deployment-time security settings for administrator initialization. */
@Validated
@ConfigurationProperties(prefix = "agenvas.identity")
public record IdentityProperties(
        @NotBlank @Size(min = 24, max = 512) String bootstrapSecret) {

    /** Refuses historical example values even outside Compose-based deployments. */
    public IdentityProperties {
        if ("local-bootstrap-secret-change-me".equals(bootstrapSecret)
                || "replace-with-a-long-random-bootstrap-secret".equals(bootstrapSecret)) {
            throw new IllegalArgumentException(
                    "Administrator bootstrap secret must be deployment-specific");
        }
    }
}
