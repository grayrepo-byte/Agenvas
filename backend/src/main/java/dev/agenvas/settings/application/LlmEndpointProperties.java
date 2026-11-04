package dev.agenvas.settings.application;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Deployment-controlled HTTP exceptions; administrators cannot expand these from the UI.
 * @param allowLoopbackHttp whether the exact IPv4 loopback may use HTTP
 * @param trustedHttpOrigins exact HTTP origins (host and port), empty by default
 */
@ConfigurationProperties(prefix = "agenvas.settings.llm")
public record LlmEndpointProperties(boolean allowLoopbackHttp, List<String> trustedHttpOrigins) {
    @ConstructorBinding
    public LlmEndpointProperties {
        trustedHttpOrigins = trustedHttpOrigins == null ? List.of() : List.copyOf(trustedHttpOrigins);
    }

    public LlmEndpointProperties(boolean allowLoopbackHttp) {
        this(allowLoopbackHttp, List.of());
    }
}
