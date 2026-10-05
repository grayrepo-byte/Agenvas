package dev.agenvas.shared.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

/**
 * 分类器的价值全在「不误判」上：判成超时会给出 {@code PROVIDER_CALL_TIMEOUT}，判错就把
 * 线程中断、okio deadline 或主动取消说成了等待超时。这里按 OkHttp/okio 实际抛出的文案
 * 逐一锁定，包括真实触发时挂上的 cause 与 suppressed。
 */
class OutboundTimeoutsTest {

    @Test
    void socketTimeoutIsATimeout() {
        assertThat(OutboundTimeouts.isTimeout(new SocketTimeoutException("Read timed out")))
                .isTrue();
        assertThat(OutboundTimeouts.isTimeout(new SocketTimeoutException("Connect timed out")))
                .isTrue();
    }

    /** OkHttp 的整次调用计时器用固定消息 {@code timeout}。 */
    @Test
    void okhttpCallTimeoutIsATimeout() {
        assertThat(OutboundTimeouts.isTimeout(new InterruptedIOException("timeout"))).isTrue();
    }

    /** 线程真被中断、okio deadline 与主动取消都不是超时，理由各不相同。 */
    @Test
    void otherInterruptionsAreNotTimeouts() {
        assertThat(OutboundTimeouts.isTimeout(new InterruptedIOException("interrupted")))
                .isFalse();
        assertThat(OutboundTimeouts.isTimeout(new InterruptedIOException("deadline reached")))
                .isFalse();
        assertThat(OutboundTimeouts.isTimeout(new IOException("Canceled"))).isFalse();
        assertThat(OutboundTimeouts.isTimeout(new IOException("unexpected end of stream")))
                .isFalse();
    }

    /** OkHttp 触发整次调用超时时把底层 socket 异常挂在 cause 上，只看最外层会漏判。 */
    @Test
    void timeoutNestedAsCauseIsDetected() {
        IOException wrapper = new IOException("connection failed",
                new SocketTimeoutException("timeout"));

        assertThat(OutboundTimeouts.isTimeout(wrapper)).isTrue();
    }

    /** OkHttp 在取消路径里用 suppress 附加原因，同样要能穿透。 */
    @Test
    void timeoutAttachedAsSuppressedIsDetected() {
        IOException wrapper = new IOException("call failed");
        wrapper.addSuppressed(new InterruptedIOException("timeout"));

        assertThat(OutboundTimeouts.isTimeout(wrapper)).isTrue();
    }

    /** 消息只做精确比对：形近但语义不同的文案不能被当成超时。 */
    @Test
    void messagesThatMerelyMentionTimeoutAreNotMatched() {
        assertThat(OutboundTimeouts.isTimeout(
                new InterruptedIOException("timed out while reading"))).isFalse();
        assertThat(OutboundTimeouts.isTimeout(
                new InterruptedIOException("timeout reached"))).isFalse();
        assertThat(OutboundTimeouts.isTimeout(new InterruptedIOException(null))).isFalse();
    }
}
