package dev.agenvas.identity.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Bounded single-node limiter for repeated authentication and password failures. */
@Component
public class AuthenticationAttemptLimiter {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, FailureWindow> failures = new ConcurrentHashMap<>();

    public AuthenticationAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Rejects attempts whose key exceeded the fixed failure window. */
    public void checkAllowed(String key) {
        Instant now = clock.instant();
        FailureWindow window = failures.get(key);
        if (window == null || window.startedAt().plus(WINDOW).isBefore(now)) {
            failures.remove(key, window);
            return;
        }
        if (window.failures() >= MAX_FAILURES) {
            throw new ApiProblemException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "AUTH_RATE_LIMITED",
                    "尝试次数过多",
                    "请稍后再试。",
                    true);
        }
    }

    /** Records a failed attempt while keeping memory use bounded. */
    public void recordFailure(String key) {
        Instant now = clock.instant();
        if (failures.size() >= MAX_TRACKED_KEYS && !failures.containsKey(key)) {
            failures.entrySet().removeIf(entry -> entry.getValue().startedAt().plus(WINDOW).isBefore(now));
            if (failures.size() >= MAX_TRACKED_KEYS) {
                return;
            }
        }
        failures.compute(key, (ignored, current) -> {
            if (current == null || current.startedAt().plus(WINDOW).isBefore(now)) {
                return new FailureWindow(1, now);
            }
            return new FailureWindow(current.failures() + 1, current.startedAt());
        });
    }

    /** Clears the failure record after successful authentication. */
    public void reset(String key) {
        failures.remove(key);
    }

    private record FailureWindow(int failures, Instant startedAt) {}
}
