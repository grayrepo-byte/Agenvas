package dev.agenvas.provider.domain;

/** Submission outcomes must be recorded by the task state machine, never by adapters. */
public sealed interface Submission {
    record Accepted(String requestId) implements Submission {}
    record Completed(MediaPayload payload) implements Submission {}
    record CompletedArtifact(tools.jackson.databind.JsonNode content) implements Submission {}
    record Pending(java.time.Instant nextActionAt) implements Submission {}
    record Blocked(String code) implements Submission {}
    record Rejected(String code) implements Submission {}
    record Unknown(String code) implements Submission {}
}
