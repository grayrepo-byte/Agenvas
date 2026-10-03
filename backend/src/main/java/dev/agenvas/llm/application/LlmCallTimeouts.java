package dev.agenvas.llm.application;

import java.time.Duration;

/** Per-request deadlines shared by model workers and outbound transports. */
public final class LlmCallTimeouts {
    /** Finite model-request allowance; it does not limit the complete AgentRun. */
    public static final Duration MODEL_REQUEST = Duration.ofMinutes(10);
    /** Maximum silence while reading an admitted model response. */
    public static final Duration TRANSPORT_READ = Duration.ofSeconds(180);

    private LlmCallTimeouts() {}
}
