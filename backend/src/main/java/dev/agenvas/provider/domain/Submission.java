package dev.agenvas.provider.domain;

/** Submission outcomes must be recorded by the task state machine, never by adapters. */
public sealed interface Submission {
    record Accepted(String requestId) implements Submission {}
    record Completed(MediaPayload payload) implements Submission {}
    record Rejected(String code) implements Submission {}
    record Unknown(String code) implements Submission {}
}
