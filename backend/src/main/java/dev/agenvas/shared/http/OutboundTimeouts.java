package dev.agenvas.shared.http;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

/**
 * 判定出站调用失败是否属于「等待超时」，供调用方给出可区分的失败原因码。
 *
 * <p>必须同时认两种形态，因为 OkHttp 对它们用了不同的异常：
 * <ul>
 *   <li>{@code readTimeout} / {@code connectTimeout} 由 socket 的 SO_TIMEOUT 触发，
 *       抛 {@link SocketTimeoutException}（HTTP/2 下由 OkHttp 显式构造）；</li>
 *   <li>{@code callTimeout} 由 OkHttp 的整次调用计时器触发，抛消息为 {@code timeout} 的
 *       {@link InterruptedIOException}。</li>
 * </ul>
 *
 * <p>消息必须精确相等而不是包含：okio 的其它超时形态用的是 {@code interrupted} 与
 * {@code deadline reached}，线程真被中断时也不能被误记成调用超时。只看消息相等即可
 * 区分，不必依赖异常层级的巧合。
 *
 * <p>要遍历整条 cause 链与 {@code suppressed}：OkHttp 触发整次调用超时时会用
 * {@code initCause} 挂上底层的 socket 异常，并在取消路径里用 {@code addSuppressed}
 * 附加原因，只看最外层会漏判。
 */
public final class OutboundTimeouts {

    /** OkHttp 整次调用超时时使用的固定消息。 */
    private static final String OKHTTP_CALL_TIMEOUT_MESSAGE = "timeout";

    private OutboundTimeouts() {}

    /**
     * @param failure 出站调用抛出的异常
     * @return 该失败是否为等待超时
     */
    public static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (isTimeoutItself(cause)) {
                return true;
            }
            for (Throwable suppressed : cause.getSuppressed()) {
                if (isTimeoutItself(suppressed)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTimeoutItself(Throwable cause) {
        if (cause instanceof SocketTimeoutException) {
            return true;
        }
        return cause instanceof InterruptedIOException
                && OKHTTP_CALL_TIMEOUT_MESSAGE.equals(cause.getMessage());
    }
}
