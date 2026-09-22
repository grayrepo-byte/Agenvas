package dev.agenvas.provider.domain;

public record GenerationResult(
        Status status, String providerRequestId, boolean demoOutput, String errorCode) {

    public enum Status {
        ACCEPTED,
        FAILED,
        UNKNOWN
    }
}
