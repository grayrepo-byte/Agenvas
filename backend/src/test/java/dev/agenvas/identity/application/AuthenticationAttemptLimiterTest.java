package dev.agenvas.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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

        assertRateLimited(limiter, key);
    }

    @Test
    void untrackedKeysShareALimitAtCapacityAndCanTryAgainAfterItExpires() {
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-09-23T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(clock);
        for (int index = 0; index < 10_000; index++) {
            limiter.recordFailure("fixture-" + index);
        }

        for (int index = 0; index < 5; index++) {
            String key = "overflow-" + index;
            limiter.checkAllowed(key);
            limiter.recordFailure(key);
        }

        assertRateLimited(limiter, "another-untracked-key");
        assertThatCode(() -> limiter.checkAllowed("fixture-0")).doesNotThrowAnyException();
        limiter.reset("fixture-1");
        assertRateLimited(limiter, "another-untracked-key");

        when(clock.instant()).thenReturn(start.plusSeconds(5 * 60));
        for (int index = 0; index < 5; index++) {
            limiter.checkAllowed("another-untracked-key");
            limiter.recordFailure("another-untracked-key");
        }
        assertRateLimited(limiter, "another-untracked-key");
        assertThatCode(() -> limiter.checkAllowed("separate-new-key")).doesNotThrowAnyException();
    }

    private void assertRateLimited(AuthenticationAttemptLimiter limiter, String key) {
        assertThatThrownBy(() -> limiter.checkAllowed(key))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.code()).isEqualTo("AUTH_RATE_LIMITED");
                });
    }
}
