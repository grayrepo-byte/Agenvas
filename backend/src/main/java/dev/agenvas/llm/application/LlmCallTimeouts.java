package dev.agenvas.llm.application;

import dev.agenvas.shared.http.OutboundTimeouts;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** Per-request deadlines shared by model workers and outbound transports. */
public final class LlmCallTimeouts {
    /** Finite model-request allowance; it does not limit the complete AgentRun. */
    public static final Duration REQUEST = Duration.ofMinutes(10);
    public static final Duration CONNECT = Duration.ofSeconds(10);
    /** Maximum silence while reading an admitted model response. */
    public static final Duration TRANSPORT_READ = Duration.ofSeconds(180);
    public static final String ERROR_CODE = "LLM_CALL_TIMEOUT";

    private LlmCallTimeouts() {}

    public static boolean isTimeout(Throwable failure) {
        if (OutboundTimeouts.isTimeout(failure)) return true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) return true;
        }
        return false;
    }
}
