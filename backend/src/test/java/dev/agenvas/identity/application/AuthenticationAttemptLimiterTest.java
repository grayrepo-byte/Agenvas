package dev.agenvas.identity.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuthenticationAttemptLimiterTest {

    @Test
    void blocksTheSixthAttemptAfterFiveFailures() {
        AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(
                Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC));
        String key = "login:127.0.0.1:admin";

        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.checkAllowed(key);
            limiter.recordFailure(key);
        }

        assertThatThrownBy(() -> limiter.checkAllowed(key))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("AUTH_RATE_LIMITED");
    }
}
