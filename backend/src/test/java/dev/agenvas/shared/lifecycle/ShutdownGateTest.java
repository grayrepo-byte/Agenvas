package dev.agenvas.shared.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A close signal waits for a short in-flight claim, then admits no later claim. */
class ShutdownGateTest {

    @Test
    void existingClaimCompletesBeforeCloseAndLaterClaimDoesNotRun() throws Exception {
        ShutdownGate gate = new ShutdownGate();
        CountDownLatch claimEntered = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        AtomicInteger laterCalls = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var claimed = pool.submit(() -> gate.claimOrEmpty(() -> {
                claimEntered.countDown();
                try {
                    assertThat(releaseClaim.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return 7;
            }, 0));
            assertThat(claimEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var closing = pool.submit(() -> gate.onContextClosed(null));
            assertThatThrownBy(() -> closing.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseClaim.countDown();
            assertThat(claimed.get(5, TimeUnit.SECONDS)).isEqualTo(7);
            closing.get(5, TimeUnit.SECONDS);
            assertThat(gate.isClosing()).isTrue();
            assertThat(gate.claimOrEmpty(laterCalls::incrementAndGet, 0)).isZero();
            assertThat(laterCalls).hasValue(0);
        }
    }
}
