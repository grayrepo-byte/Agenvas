package dev.agenvas.provider.application;

import dev.agenvas.provider.infrastructure.ComfyUiEndpoint;
import org.springframework.stereotype.Component;

/** Read-only application boundary for the existing ComfyUI endpoint policy. */
@Component
public class ComfyUiEndpointPolicy {
    public boolean allows(String endpoint) {
        try {
            ComfyUiEndpoint.checked(endpoint);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
