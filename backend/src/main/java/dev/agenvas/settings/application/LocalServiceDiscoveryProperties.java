package dev.agenvas.settings.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Deployment controls discovery independently of endpoint HTTP authorization. */
@ConfigurationProperties(prefix = "agenvas.settings.local-services")
public record LocalServiceDiscoveryProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("AUTO") Mode runtime) {
    public enum Mode { AUTO, CONTAINER, NATIVE }

    public LocalServiceDiscoveryProperties {
        runtime = runtime == null ? Mode.AUTO : runtime;
    }
}
