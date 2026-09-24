package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.MockFixture;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-owned deterministic Mock outcome; model proposals cannot choose a fixture. */
@ConfigurationProperties(prefix = "agenvas.provider.mock")
public record MockProviderProperties(MockFixture fixture) {

    /** Normal deployments demonstrate success unless an operator selects a fault fixture. */
    public MockProviderProperties {
        fixture = fixture == null ? MockFixture.SUCCESS : fixture;
    }
}
