package dev.agenvas.llm.application;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.io.IOException;
import org.springframework.http.HttpStatus;

/** A clean HTTP EOF does not prove that the selected SSE completion finished. */
public final class LlmStreamIncompleteException extends IOException {
    public static final String ERROR_CODE = "LLM_STREAM_INCOMPLETE";

    public LlmStreamIncompleteException() {
        super("Model stream ended without a completion signal");
    }

    /** The SDK wraps transport failures; inspect types, never provider-controlled text. */
    public static boolean matches(Throwable failure) {
        int depth = 0;
        for (Throwable cause = failure; cause != null && depth++ < 64; cause = cause.getCause()) {
            if (cause instanceof LlmStreamIncompleteException)
                return true;
        }
        return false;
    }

    public static ApiProblemException problem() {
        return new ApiProblemException(HttpStatus.BAD_GATEWAY, ERROR_CODE,
                ApiMessage.of("api.llm-stream.incomplete-title"),
                ApiMessage.of("api.llm-stream.incomplete-detail"), false);
    }
}
