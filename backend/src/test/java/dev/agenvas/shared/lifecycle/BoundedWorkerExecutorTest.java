package dev.agenvas.shared.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class BoundedWorkerExecutorTest {
    private final ShutdownGate gate = new ShutdownGate();
    private final Logger logger = mock(Logger.class);

    @Test
    void skipsConcurrentTicksWithoutQueueingAndStopsAcceptingAfterClose() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> passThread = new AtomicReference<>();
        AtomicInteger unexpectedPasses = new AtomicInteger();
        var worker = new BoundedWorkerExecutor(gate, "archive-test", logger, "Pass failed: {}");
        try (worker) {
            worker.execute(() -> {
                passThread.set(Thread.currentThread());
                entered.countDown();
                await(release);
            });
            try {
                await(entered);
                assertThat(passThread.get().getName()).isEqualTo("archive-test");
                assertThat(passThread.get().isVirtual()).isFalse();
                worker.execute(unexpectedPasses::incrementAndGet);
            } finally {
                release.countDown();
            }
        }
        worker.execute(unexpectedPasses::incrementAndGet);
        worker.close();
        assertThat(unexpectedPasses).hasValue(0);
    }

    @Test
    void recoversAfterSubmissionRejectionAndTaskFailureWithClassOnlyWarning() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException()).doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any());
        when(executor.awaitTermination(10, TimeUnit.SECONDS)).thenReturn(true);
        AtomicInteger completed = new AtomicInteger();
        try (var worker = new BoundedWorkerExecutor(gate, executor, logger, "Pass failed: {}")) {
            worker.execute(completed::incrementAndGet);
            worker.execute(() -> {
                throw new IllegalStateException("private details");
            });
            worker.execute(completed::incrementAndGet);
        }
        assertThat(completed).hasValue(1);
        verify(logger).warn("Pass failed: {}", "IllegalStateException");
    }

    @Test
    void checksShutdownBeforeSubmissionAndAgainBeforeStartingThePass() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        doAnswer(invocation -> {
            submitted.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any());
        when(executor.awaitTermination(10, TimeUnit.SECONDS)).thenReturn(true);
        AtomicInteger executed = new AtomicInteger();
        try (var worker = new BoundedWorkerExecutor(gate, executor, logger, "Pass failed: {}")) {
            worker.execute(executed::incrementAndGet);
            gate.onContextClosed(null);
            submitted.getAndSet(null).run();
            worker.execute(executed::incrementAndGet);
        }
        assertThat(executed).hasValue(0);
        assertThat(submitted).hasValue(null);
    }

    @Test
    void closeWaitsForTenSecondsAndForcesShutdownWhenTheWaitExpires() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        var worker = new BoundedWorkerExecutor(gate, executor, logger, "Pass failed: {}");
        worker.close();
        var close = inOrder(executor);
        close.verify(executor).shutdown();
        close.verify(executor).awaitTermination(10, TimeUnit.SECONDS);
        close.verify(executor).shutdownNow();
    }

    @Test
    void interruptedCloseForcesShutdownAndRestoresTheInterrupt() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.awaitTermination(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        var worker = new BoundedWorkerExecutor(gate, executor, logger, "Pass failed: {}");
        try {
            worker.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(executor).shutdownNow();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
