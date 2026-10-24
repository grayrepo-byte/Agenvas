package dev.agenvas.llm.application;

import com.openai.errors.OpenAIServiceException;
import java.util.Locale;

/** Safe classification of an explicit model protocol rejection, without publishing its response body. */
final class LlmMediaInputFailures {
    static final String ERROR_CODE = "LLM_MEDIA_INPUT_UNSUPPORTED";

    private LlmMediaInputFailures() {}

    static boolean matches(Throwable failure) {
        boolean modelBoundary = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelCallFailure) modelBoundary = true;
            if (!modelBoundary || !(cause instanceof OpenAIServiceException service)) continue;
            if (service.statusCode() != 400 && service.statusCode() != 422) return false;
            String message = service.getMessage();
            if (message == null) return false;
            String normalized = message.toLowerCase(Locale.ROOT);
            return normalized.contains("unknown variant `input_audio`")
                    || (normalized.contains("input_audio")
                            && (normalized.contains("not supported") || normalized.contains("unsupported")))
                    || normalized.contains("does not support audio input")
                    || normalized.contains("audio input is not supported");
        }
        return false;
    }
}
