package dev.agenvas.provider.domain;

import java.util.Objects;
import java.util.UUID;

public record GenerationRequest(UUID projectId, String requestKey, MockFixture fixture) {

    public GenerationRequest {
        Objects.requireNonNull(projectId, "projectId must not be null");
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException("requestKey must not be blank");
        }
        Objects.requireNonNull(fixture, "fixture must not be null");
    }
}
