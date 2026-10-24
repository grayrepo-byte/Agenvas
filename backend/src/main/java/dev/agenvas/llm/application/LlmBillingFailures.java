package dev.agenvas.llm.application;

import com.openai.errors.OpenAIServiceException;
import java.util.Locale;
import java.util.Set;

/** Recognizes billing failures only at the model gateway; provider text is never published. */
final class LlmBillingFailures {
    static final String ERROR_CODE = "LLM_INSUFFICIENT_CREDITS";
    private static final Set<String> CODES = Set.of("insufficient_quota", "insufficient_balance",
            "insufficient_credits", "credit_balance_too_low");

    private LlmBillingFailures() {}

    static boolean matches(Throwable failure) {
        boolean modelBoundary = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelCallFailure) modelBoundary = true;
            if (!modelBoundary || !(cause instanceof OpenAIServiceException service)) continue;
            int status = service.statusCode();
            if (status < 400 || status >= 500) return false;
            if (status == 402 || service.code().filter(CODES::contains).isPresent()
                    || service.type().filter(CODES::contains).isPresent()) return true;
            String message = service.getMessage();
            if (message == null) return false;
            String normalized = message.toLowerCase(Locale.ROOT);
            return normalized.contains("insufficient credits") || normalized.contains("insufficient balance")
                    || normalized.contains("credit balance is too low") || normalized.contains("余额不足");
        }
        return false;
    }
}
