package dev.agenvas.llm.application;

import dev.agenvas.shared.http.OutboundTimeouts;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** One bounded model-request deadline shared by the Worker, SDK and admitted transport. */
public final class LlmCallTimeouts {
    public static final Duration REQUEST = Duration.ofSeconds(90);
    public static final Duration CONNECT = Duration.ofSeconds(10);
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
