package dev.agenvas.llm.application;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Bounded retries of one frozen model request; never applies to media or committed tools. */
public final class AgentModelRetryPolicy {
    public static final String PROPERTY = Task.MODEL_RETRY_PROPERTY;
    public static final String EXHAUSTED_CODE = "LLM_RETRY_EXHAUSTED";
    public static final int MAX_RETRIES = 10;
    public static final Duration WINDOW = Duration.ofMinutes(5);
    private static final int SCHEMA_VERSION = 1;
    private static final Duration BASE_DELAY = Duration.ofSeconds(2);
    private static final Duration MAX_DELAY = Duration.ofSeconds(30);
    private AgentModelRetryPolicy() {}

    /** HTTP status takes precedence over nested I/O, so invalid/authenticated requests never retry. */
    static String retryableCode(Throwable failure) {
        boolean modelBoundary = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelCallFailure || cause instanceof TimeoutException) modelBoundary = true;
            if (cause instanceof ApiProblemException) return null;
        }
        if (!modelBoundary) return null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                if (status == HttpStatus.TOO_MANY_REQUESTS.value()) return "LLM_RATE_LIMITED";
                return status == HttpStatus.REQUEST_TIMEOUT.value() || HttpStatus.Series.resolve(status) == HttpStatus.Series.SERVER_ERROR ? "LLM_SERVICE_UNAVAILABLE" : null;
            }
        }
        if (LlmCallTimeouts.isTimeout(failure)) return LlmCallTimeouts.ERROR_CODE;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException || cause instanceof OpenAIIoException) return "LLM_CONNECTION_FAILED";
        }
        return null;
    }

    static JsonNode progress(Task task) {
        return task.output() == null ? null : task.output().get(PROPERTY);
    }
    static Duration remaining(Task task, Instant now) {
        JsonNode retry = progress(task);
        return retry == null ? LlmCallTimeouts.REQUEST
                : Duration.between(now, Instant.parse(retry.path("deadlineAt").asText()));
    }
    static Decision afterFailure(Task task, String code, Instant now, ObjectMapper mapper) {
        JsonNode previous = progress(task);
        Instant first = previous == null ? now : Instant.parse(previous.path("firstFailureAt").asText());
        Instant deadline = first.plus(WINDOW);
        int count = previous == null ? 0 : previous.path("retryCount").asInt();
        boolean exhausted = count >= MAX_RETRIES || !now.isBefore(deadline);
        Instant next = exhausted ? now : now.plus(delay(count + 1));
        // Schedule the deadline itself if no retry fits; the worker expires without a network call.
        if (next.isAfter(deadline)) next = deadline;
        ObjectNode retry = mapper.createObjectNode();
        retry.put("schemaVersion", SCHEMA_VERSION);
        retry.put("retryCount", exhausted ? count : count + 1);
        retry.put("maxRetries", MAX_RETRIES);
        retry.put("firstFailureAt", first.toString());
        retry.put("deadlineAt", deadline.toString());
        retry.put("lastErrorCode", code);
        return new Decision(retry, next, exhausted);
    }
    static Duration delay(int retry) {
        Duration exponential = BASE_DELAY.multipliedBy(1L << Math.min(retry - 1, MAX_RETRIES));
        return exponential.compareTo(MAX_DELAY) < 0 ? exponential : MAX_DELAY;
    }
    record Decision(ObjectNode progress, Instant nextActionAt, boolean exhausted) {}
}
