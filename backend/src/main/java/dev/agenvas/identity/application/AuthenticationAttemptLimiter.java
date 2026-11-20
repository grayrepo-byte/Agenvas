package dev.agenvas.identity.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 限制单节点内重复的登录和密码失败次数，并限制跟踪键数量避免无界占用内存。 */
@Component
public class AuthenticationAttemptLimiter {

    /** 固定窗口内允许的失败次数上限。 */
    private static final int MAX_FAILURES = 5;
    /** 连续失败计数窗口长度，过期后首次失败会开启新窗口。 */
    private static final Duration WINDOW = Duration.ofMinutes(5);
    /** 内存中可同时保留的不同登录或密码操作键上限。 */
    private static final int MAX_TRACKED_KEYS = 10_000;

    /** 提供可控的窗口过期判断时钟。 */
    private final Clock clock;
    /** 按来源和操作标识记录窗口内失败数，成功时移除。 */
    private final Map<String, FailureWindow> failures = new HashMap<>();
    /** Keep counting new keys together until the overflow window expires, even if a slot frees up. */
    private FailureWindow overflow;

    /** 注入尝试窗口所用时钟。 */
    public AuthenticationAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 达到对应键或共享窗口的失败次数上限时返回 429。 */
    public synchronized void checkAllowed(String key) {
        Instant now = clock.instant();
        FailureWindow window = failures.get(key);
        if (window == null || window.expired(now))
            window = overflow;
        if (window != null && !window.expired(now) && window.failures() >= MAX_FAILURES) {
            throw new ApiProblemException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "AUTH_RATE_LIMITED",
                    ApiMessage.of("api.authentication-attempt-limiter.too-many-attempts"),
                    ApiMessage.of("api.authentication-attempt-limiter.please-try-again-later"),
                    true);
        }
    }

    /** 表满时清除过期项，仍无空位则为新键启用共享窗口。 */
    public synchronized void recordFailure(String key) {
        Instant now = clock.instant();
        FailureWindow current = failures.get(key);
        if (current != null && !current.expired(now)) {
            failures.put(key, current.increment());
            return;
        }
        failures.remove(key);
        if (overflow != null && !overflow.expired(now)) {
            overflow = overflow.increment();
            return;
        }
        overflow = null;
        if (failures.size() >= MAX_TRACKED_KEYS) {
            failures.values().removeIf(window -> window.expired(now));
            if (failures.size() >= MAX_TRACKED_KEYS) {
                overflow = new FailureWindow(1, now);
                return;
            }
        }
        failures.put(key, new FailureWindow(1, now));
    }

    /** 成功认证或密码更新后清除该操作键的失败历史。 */
    public synchronized void reset(String key) {
        failures.remove(key);
    }

    /** 一个固定窗口内的失败计数。
     * @param failures 当前窗口累计失败次数
     * @param startedAt 窗口起始时刻
     */
    private record FailureWindow(int failures, Instant startedAt) {
        boolean expired(Instant now) {
            return !now.isBefore(startedAt.plus(WINDOW));
        }

        FailureWindow increment() {
            return failures >= MAX_FAILURES ? this : new FailureWindow(failures + 1, startedAt);
        }
    }
}
