package dev.agenvas.settings.application;

import java.util.List;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Deployment-controlled HTTP exceptions; administrators cannot expand these from the UI.
 * @param allowLoopbackHttp whether the exact IPv4 loopback may use HTTP
 * @param allowHttp whether ordinary HTTP services are allowed, true by default
 * @param trustedHttpOrigins exact HTTP origins (host and port), empty by default
 */
@ConfigurationProperties(prefix = "agenvas.settings.llm")
public record LlmEndpointProperties(boolean allowLoopbackHttp, List<String> trustedHttpOrigins,
        @DefaultValue("true") boolean allowHttp) {
    @ConstructorBinding
    public LlmEndpointProperties {
        trustedHttpOrigins = trustedHttpOrigins == null ? List.of() : List.copyOf(trustedHttpOrigins);
    }

    public LlmEndpointProperties(boolean allowLoopbackHttp, List<String> trustedHttpOrigins) {
        this(allowLoopbackHttp, trustedHttpOrigins, true);
    }

    public LlmEndpointProperties(boolean allowLoopbackHttp) {
        this(allowLoopbackHttp, List.of(), true);
    }
}
