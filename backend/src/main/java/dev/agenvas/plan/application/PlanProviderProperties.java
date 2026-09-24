package dev.agenvas.plan.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-owned Provider mode and version recorded in every approval snapshot. */
@ConfigurationProperties(prefix = "agenvas.provider")
public record PlanProviderProperties(String mode, int configVersion) {

    /** Invalid config must fail startup rather than create unverifiable plans. */
    public PlanProviderProperties {
        if (mode == null || mode.isBlank() || configVersion < 1) {
            throw new IllegalArgumentException("Provider mode and positive config version are required");
        }
    }
}
