package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real bounded model threads and explicitly triggered renewals prove local lifecycle races. */
class LeasedModelExecutorTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String WORKER = "synthetic-worker";

    @ParameterizedTest
    @CsvSource({"300, 1000", "30000, 10000", "1800000, 600000"})
    void heartbeatUsesTheLeaseIntervalAndCanceledSchedulesLeaveNoQueueEntry(
            long leaseMillis, long intervalMillis) throws Exception {
        try (Fixture fixture = new Fixture(Duration.ofMillis(leaseMillis))) {
            var execution = fixture.open();
            assertThat(fixture.heartbeats.initialDelayMillis).isEqualTo(intervalMillis);
            assertThat(fixture.heartbeats.periodMillis).isEqualTo(intervalMillis);
            assertThat(fixture.heartbeats.getRemoveOnCancelPolicy()).isTrue();
            assertThat(fixture.heartbeats.getQueue()).hasSize(1);

            execution.close();

            assertThat(fixture.heartbeats.getQueue()).isEmpty();
        }
    }

    @Test
    void completedCallsAndModelExceptionsKeepTheirOriginalResults() throws Exception {
        try (Fixture fixture = new Fixture(); var execution = fixture.open()) {
            assertThat(execution.call(() -> "synthetic response", WAIT)).isEqualTo("synthetic response");
            IllegalArgumentException modelFailure = new IllegalArgumentException("Synthetic model error");
            assertThatThrownBy(() -> execution.call(() -> { throw modelFailure; }, WAIT))
                    .isInstanceOf(ExecutionException.class).hasCause(modelFailure);
            execution.checkActive();
        }
    }

    @Test
    void timeoutCancelsTheRunningCallWithoutTurningItIntoALocalStop() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(); var execution = fixture.open()) {
            try {
                Future<String> owner = fixture.callers.submit(() -> execution.call(() -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException cancellation) {
                        interrupted.countDown();
                        throw cancellation;
                    }
                    return "late response";
                }, Duration.ofMillis(250)));
                await(entered);
                assertThatThrownBy(() -> owner.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(TimeoutException.class);
                await(interrupted);
                execution.checkActive();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void heartbeatFailureWakesTheWaiterEvenWhenTheProviderIgnoresInterruption() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(); var execution = fixture.open()) {
            IllegalStateException renewalFailure = new IllegalStateException("Synthetic renewal failure");
            doThrow(renewalFailure).when(fixture.tasks).heartbeat(fixture.taskId, WORKER, 7);
            try {
                Future<String> owner = fixture.callers.submit(() -> execution.call(() -> {
                    entered.countDown();
                    awaitUninterruptibly(release);
                    return "late response";
                }, WAIT));
                await(entered);

                fixture.heartbeats.tick(0);

                assertThatThrownBy(() -> owner.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(LeasedModelExecutor.Stopped.class)
                        .rootCause().isSameAs(renewalFailure);
                assertThat(fixture.models.getActiveCount()).isEqualTo(1);
                assertThatThrownBy(execution::checkActive).isInstanceOf(LeasedModelExecutor.Stopped.class);
                verify(fixture.tasks).heartbeat(fixture.taskId, WORKER, 7);
                verifyNoMoreInteractions(fixture.tasks);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void aRenewalFailureAfterTheResponseStopsOnlySubsequentLocalActions() throws Exception {
        try (Fixture fixture = new Fixture(); var execution = fixture.open()) {
            String completed = execution.call(() -> "durable response", WAIT);
            doThrow(new IllegalStateException("Synthetic renewal failure"))
                    .when(fixture.tasks).heartbeat(fixture.taskId, WORKER, 7);

            fixture.heartbeats.tick(0);

            assertThat(completed).isEqualTo("durable response");
            assertThatThrownBy(execution::checkActive).isInstanceOf(LeasedModelExecutor.Stopped.class);
        }
    }

    @Test
    void heartbeatStopWinsOverAModelFailureWhichArrivesAfterward() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(); var execution = fixture.open()) {
            IllegalStateException renewalFailure = new IllegalStateException("Synthetic renewal failure");
            doThrow(renewalFailure).when(fixture.tasks).heartbeat(fixture.taskId, WORKER, 7);
            try {
                Future<String> owner = fixture.callers.submit(() -> execution.call(() -> {
                    running.countDown();
                    awaitUninterruptibly(releaseFailure);
                    failed.countDown();
                    throw new IllegalArgumentException("Synthetic late model failure");
                }, WAIT));
                await(running);

                fixture.heartbeats.tick(0);
                releaseFailure.countDown();
                await(failed);

                assertThatThrownBy(() -> owner.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(LeasedModelExecutor.Stopped.class)
                        .rootCause().isSameAs(renewalFailure);
            } finally {
                releaseFailure.countDown();
            }
        }
    }

    @Test
    void normalCloseSuppressesARenewalFailureAlreadyInFlight() throws Exception {
        CountDownLatch renewing = new CountDownLatch(1);
        CountDownLatch releaseRenewal = new CountDownLatch(1);
        try (Fixture fixture = new Fixture()) {
            var execution = fixture.open();
            doAnswer(invocation -> {
                renewing.countDown();
                await(releaseRenewal);
                throw new IllegalStateException("Synthetic late renewal failure");
            }).when(fixture.tasks).heartbeat(fixture.taskId, WORKER, 7);
            try {
                Future<?> renewal = fixture.callers.submit(() -> fixture.heartbeats.tick(0));
                await(renewing);

                execution.close();
                releaseRenewal.countDown();
                renewal.get(5, TimeUnit.SECONDS);

                assertThatThrownBy(execution::checkActive)
                        .isInstanceOf(LeasedModelExecutor.Stopped.class).hasMessage("Model execution is closed")
                        .hasNoCause();
                try (var next = fixture.open()) {
                    assertThat(next.call(() -> "next response", WAIT)).isEqualTo("next response");
                }
            } finally {
                releaseRenewal.countDown();
                execution.close();
            }
        }
    }

    @Test
    void stoppingAQueuedCallFreesItsSlotWithoutAddingCapacityAroundAnUncooperativeProvider()
            throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedCallRan = new AtomicBoolean();
        try (Fixture fixture = new Fixture(); var first = fixture.open(); var queued = fixture.open()) {
            try {
                Future<String> oldOwner = fixture.callers.submit(() -> first.call(() -> {
                    running.countDown();
                    awaitUninterruptibly(release);
                    return "old response";
                }, WAIT));
                await(running);
                fixture.models.awaitSubmission();
                Future<String> queuedOwner = fixture.callers.submit(() -> queued.call(() -> {
                    queuedCallRan.set(true);
                    return "never sent";
                }, WAIT));
                fixture.models.awaitSubmission();
                assertThat(fixture.models.getQueue()).hasSize(1);

                doThrow(new IllegalStateException("Synthetic renewal failure"))
                        .when(fixture.tasks).heartbeat(fixture.taskId, WORKER, 7);
                fixture.heartbeats.tick(1);

                assertThatThrownBy(() -> queuedOwner.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(LeasedModelExecutor.Stopped.class);
                assertThat(fixture.models.getQueue()).isEmpty();
                assertThat(fixture.models.getActiveCount()).isEqualTo(1);
                assertThat(fixture.models.getPoolSize()).isEqualTo(1);
                try (var next = fixture.open()) {
                    Future<String> nextOwner = fixture.callers.submit(() -> next.call(() -> "next response", WAIT));
                    fixture.models.awaitSubmission();
                    assertThat(fixture.models.getQueue()).hasSize(1);
                    release.countDown();
                    assertThat(oldOwner.get(5, TimeUnit.SECONDS)).isEqualTo("old response");
                    assertThat(nextOwner.get(5, TimeUnit.SECONDS)).isEqualTo("next response");
                }
                assertThat(queuedCallRan).isFalse();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void aQueuedTimeoutAlsoRemovesTheCanceledFutureFromTheBoundedQueue() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedCallRan = new AtomicBoolean();
        try (Fixture fixture = new Fixture(); var first = fixture.open(); var queued = fixture.open()) {
            try {
                Future<String> oldOwner = fixture.callers.submit(() -> first.call(() -> {
                    running.countDown();
                    awaitUninterruptibly(release);
                    return "old response";
                }, WAIT));
                await(running);
                fixture.models.awaitSubmission();

                assertThatThrownBy(() -> queued.call(() -> {
                    queuedCallRan.set(true);
                    return "never sent";
                }, Duration.ofMillis(50))).isInstanceOf(TimeoutException.class);

                assertThat(fixture.models.getQueue()).isEmpty();
                assertThat(queuedCallRan).isFalse();
                queued.checkActive();
                release.countDown();
                assertThat(oldOwner.get(5, TimeUnit.SECONDS)).isEqualTo("old response");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void fullCapacityStillRejectsRatherThanStartingAnotherModelThread() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean rejectedCallRan = new AtomicBoolean();
        try (Fixture fixture = new Fixture(); var first = fixture.open(); var queued = fixture.open();
                var excess = fixture.open()) {
            try {
                Future<String> firstOwner = fixture.callers.submit(() -> first.call(() -> {
                    running.countDown();
                    awaitUninterruptibly(release);
                    return "first response";
                }, WAIT));
                await(running);
                fixture.models.awaitSubmission();
                Future<String> queuedOwner = fixture.callers.submit(() -> queued.call(() -> "queued response", WAIT));
                fixture.models.awaitSubmission();

                assertThatThrownBy(() -> excess.call(() -> {
                    rejectedCallRan.set(true);
                    return "never sent";
                }, WAIT)).isInstanceOf(RejectedExecutionException.class);

                excess.checkActive();
                assertThat(fixture.models.getMaximumPoolSize()).isEqualTo(1);
                assertThat(fixture.models.getQueue()).hasSize(1);
                assertThat(rejectedCallRan).isFalse();
                release.countDown();
                assertThat(firstOwner.get(5, TimeUnit.SECONDS)).isEqualTo("first response");
                assertThat(queuedOwner.get(5, TimeUnit.SECONDS)).isEqualTo("queued response");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void executorCloseWakesRunningAndQueuedWaitersAndRejectsNewScopes() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedCallRan = new AtomicBoolean();
        try (Fixture fixture = new Fixture(); var first = fixture.open(); var queued = fixture.open()) {
            try {
                Future<String> firstOwner = fixture.callers.submit(() -> first.call(() -> {
                    running.countDown();
                    awaitUninterruptibly(release);
                    return "late response";
                }, WAIT));
                await(running);
                fixture.models.awaitSubmission();
                Future<String> queuedOwner = fixture.callers.submit(() -> queued.call(() -> {
                    queuedCallRan.set(true);
                    return "never sent";
                }, WAIT));
                fixture.models.awaitSubmission();

                fixture.executor.close();

                for (Future<?> owner : List.of(firstOwner, queuedOwner)) {
                    assertThatThrownBy(() -> owner.get(5, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(LeasedModelExecutor.Stopped.class);
                }
                assertThatThrownBy(fixture::open).isInstanceOf(LeasedModelExecutor.Stopped.class);
                assertThat(fixture.models.isShutdown()).isTrue();
                assertThat(fixture.heartbeats.isShutdown()).isTrue();
                assertThat(fixture.models.getQueue()).isEmpty();
                assertThat(fixture.heartbeats.getQueue()).isEmpty();
                assertThat(queuedCallRan).isFalse();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void ownerInterruptionIsPreservedAsInterruptedExceptionWithoutInterruptingOtherOwners()
            throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> ownerThread = new AtomicReference<>();
        try (Fixture fixture = new Fixture(); var execution = fixture.open()) {
            try {
                Future<Boolean> owner = fixture.callers.submit(() -> {
                    ownerThread.set(Thread.currentThread());
                    try {
                        execution.call(() -> {
                            running.countDown();
                            awaitUninterruptibly(release);
                            return "late response";
                        }, WAIT);
                        throw new AssertionError("Expected interrupted wait");
                    } catch (InterruptedException interrupted) {
                        // Restoring this flag is the Worker's policy, not the executor's.
                        return Thread.currentThread().isInterrupted();
                    }
                });
                await(running);

                ownerThread.get().interrupt();

                assertThat(owner.get(5, TimeUnit.SECONDS)).isFalse();
                assertThat(Thread.currentThread().isInterrupted()).isFalse();
                assertThat(fixture.models.getQueue()).isEmpty();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void independentWorkerInstancesKeepIndependentModelCapacity() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture blocked = new Fixture(); Fixture other = new Fixture();
                var first = blocked.open(); var independent = other.open()) {
            try {
                Future<String> oldOwner = blocked.callers.submit(() -> first.call(() -> {
                    running.countDown();
                    awaitUninterruptibly(release);
                    return "old response";
                }, WAIT));
                await(running);

                assertThat(independent.call(() -> "independent response", WAIT)).isEqualTo("independent response");

                release.countDown();
                assertThat(oldOwner.get(5, TimeUnit.SECONDS)).isEqualTo("old response");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void closedScopesNeverSubmitAnotherModelCall() throws Exception {
        AtomicBoolean callRan = new AtomicBoolean();
        try (Fixture fixture = new Fixture()) {
            var execution = fixture.open();
            execution.close();

            assertThatThrownBy(() -> execution.call(() -> {
                callRan.set(true);
                return "never sent";
            }, WAIT)).isInstanceOf(LeasedModelExecutor.Stopped.class);

            assertThat(callRan).isFalse();
            assertThat(fixture.models.getTaskCount()).isZero();
        }
    }

    @Test
    void aRejectedHeartbeatScheduleStopsBeforeAnyModelCall() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.heartbeats.shutdownNow();

            assertThatThrownBy(fixture::open).isInstanceOf(LeasedModelExecutor.Stopped.class)
                    .hasCauseInstanceOf(RejectedExecutionException.class);

            assertThat(fixture.models.getTaskCount()).isZero();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    /** Deliberately models an SDK/provider which cannot be stopped with thread interruption. */
    private static void awaitUninterruptibly(CountDownLatch release) {
        boolean released = false;
        while (!released) {
            try {
                released = release.await(5, TimeUnit.SECONDS);
                assertThat(released).isTrue();
            } catch (InterruptedException ignored) {
                // The enclosing test always opens the gate in finally.
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final TaskService tasks = mock(TaskService.class);
        private final UUID taskId = UUID.randomUUID();
        private final Task lease = mock(Task.class);
        private final ModelThreads models = new ModelThreads();
        private final ManualHeartbeats heartbeats = new ManualHeartbeats();
        private final ExecutorService callers = Executors.newCachedThreadPool(
                Thread.ofPlatform().daemon().name("synthetic-model-owner").factory());
        private final LeasedModelExecutor executor;

        private Fixture() {
            this(Duration.ofMinutes(30));
        }

        private Fixture(Duration leaseDuration) {
            when(lease.id()).thenReturn(taskId);
            when(lease.leaseEpoch()).thenReturn(7L);
            executor = new LeasedModelExecutor(tasks, new TaskProperties(leaseDuration, 16), models, heartbeats);
        }

        private LeasedModelExecutor.Execution open() {
            return executor.open(lease, WORKER);
        }

        @Override
        public void close() throws InterruptedException {
            executor.close();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(models.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(heartbeats.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class ModelThreads extends ThreadPoolExecutor {
        private final Semaphore submissions = new Semaphore(0);

        private ModelThreads() {
            super(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                    Thread.ofPlatform().daemon().name("synthetic-model-call").factory(), new AbortPolicy());
        }

        @Override
        public void execute(Runnable command) {
            super.execute(command);
            submissions.release();
        }

        private void awaitSubmission() throws InterruptedException {
            assertThat(submissions.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class ManualHeartbeats extends ScheduledThreadPoolExecutor {
        private final List<Runnable> renewals = new CopyOnWriteArrayList<>();
        private long initialDelayMillis;
        private long periodMillis;

        private ManualHeartbeats() {
            super(1, Thread.ofPlatform().daemon().name("synthetic-model-heartbeat").factory());
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                long period, TimeUnit unit) {
            initialDelayMillis = unit.toMillis(initialDelay);
            periodMillis = unit.toMillis(period);
            renewals.add(command);
            // Real cancellation/removal behavior, while tests control exactly when renewal runs.
            return super.scheduleAtFixedRate(command, 1, 1, TimeUnit.DAYS);
        }

        private void tick(int execution) {
            renewals.get(execution).run();
        }
    }
}
