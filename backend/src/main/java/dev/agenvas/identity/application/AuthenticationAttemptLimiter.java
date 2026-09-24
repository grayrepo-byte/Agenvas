package dev.agenvas.identity.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ConcurrentHashMap<String, FailureWindow> failures = new ConcurrentHashMap<>();

    /** 注入尝试窗口所用时钟。 */
    public AuthenticationAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 达到失败次数上限时返回 429；窗口过期的条目会被清理。 */
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

    /** 记录一次失败；键数量到顶时先清除过期项，仍超限则不再增加状态。 */
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

    /** 成功认证或密码更新后清除该操作键的失败历史。 */
    public void reset(String key) {
        failures.remove(key);
    }

    /** 一个固定窗口内的失败计数。
     * @param failures 当前窗口累计失败次数
     * @param startedAt 窗口起始时刻
     */
    private record FailureWindow(int failures, Instant startedAt) {}
}
