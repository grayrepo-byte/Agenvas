package dev.agenvas.llm.application;

import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns one Worker's bounded model capacity and the heartbeats for its leased executions. */
final class LeasedModelExecutor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LeasedModelExecutor.class);

    private final TaskService tasks;
    private final long heartbeatIntervalMillis;
    private final ThreadPoolExecutor models;
    private final ScheduledThreadPoolExecutor heartbeats;
    /** Serializes scope registration, stopping and Future registration, never a database call. */
    private final Object lifecycle = new Object();
    private final Set<Execution> executions = new HashSet<>();
    private boolean closed;

    LeasedModelExecutor(String threadPrefix, TaskService tasks, TaskProperties properties) {
        this(tasks, properties,
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                        Thread.ofPlatform().daemon().name(threadPrefix + "-model").factory(),
                        new ThreadPoolExecutor.AbortPolicy()),
                new ScheduledThreadPoolExecutor(1,
                        Thread.ofPlatform().daemon().name(threadPrefix + "-heartbeat").factory()));
    }

    /** Package-local injection keeps lifecycle tests on real bounded executors. */
    LeasedModelExecutor(TaskService tasks, TaskProperties properties,
            ThreadPoolExecutor models, ScheduledThreadPoolExecutor heartbeats) {
        this.tasks = tasks;
        this.heartbeatIntervalMillis = Math.max(1_000, properties.leaseDuration().toMillis() / 3);
        this.models = models;
        this.heartbeats = heartbeats;
        heartbeats.setRemoveOnCancelPolicy(true);
    }

    Execution open(Task lease, String workerId) {
        synchronized (lifecycle) {
            if (closed) throw new Stopped("Model executor is closed");
            Execution execution = new Execution(lease, workerId);
            executions.add(execution);
            try {
                execution.heartbeat = heartbeats.scheduleAtFixedRate(execution::renewLease,
                        heartbeatIntervalMillis, heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
                return execution;
            } catch (RuntimeException unavailable) {
                execution.close();
                throw new Stopped("Lease heartbeat could not start", unavailable);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            for (Execution execution : executions) {
                execution.stop(new Stopped("Model executor is closed"));
            }
            executions.clear();
        }
        heartbeats.shutdownNow();
        // Scope stopping cancels both running and queued Futures before the queue is drained.
        models.shutdownNow();
    }

    /** A local stop cannot prove provider cancellation or replace the final database fence. */
    static final class Stopped extends RuntimeException {
        Stopped(String message) {
            super(message);
        }

        Stopped(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final class Execution implements AutoCloseable {
        private final Task lease;
        private final String workerId;
        private ScheduledFuture<?> heartbeat;
        private FutureTask<?> call;
        private Stopped stopped;
        private boolean scopeClosed;

        private Execution(Task lease, String workerId) {
            this.lease = lease;
            this.workerId = workerId;
        }

        <T> T call(Callable<T> action, Duration timeout)
                throws InterruptedException, ExecutionException, TimeoutException {
            FutureTask<T> future = new FutureTask<>(() -> {
                checkActive();
                return action.call();
            });
            synchronized (lifecycle) {
                checkActive();
                if (call != null) throw new IllegalStateException("Model execution already has a call");
                call = future;
                try {
                    models.execute(future);
                } catch (RuntimeException rejected) {
                    cancelCall(future);
                    throw rejected;
                }
            }
            try {
                T result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                synchronized (lifecycle) {
                    checkActive();
                    return result;
                }
            } catch (CancellationException | ExecutionException | TimeoutException failure) {
                synchronized (lifecycle) {
                    // A stop that already won is not a model timeout or a retryable model error.
                    checkActive();
                    throw failure;
                }
            } finally {
                synchronized (lifecycle) {
                    cancelCall(future);
                }
            }
        }

        void checkActive() {
            synchronized (lifecycle) {
                if (stopped != null) throw stopped;
                if (scopeClosed) throw new Stopped("Model execution is closed");
            }
        }

        private void renewLease() {
            synchronized (lifecycle) {
                if (scopeClosed || stopped != null) return;
            }
            try {
                tasks.heartbeat(lease.id(), workerId, lease.leaseEpoch());
            } catch (RuntimeException unconfirmed) {
                synchronized (lifecycle) {
                    // A renewal already in progress may fail after normal completion.
                    if (scopeClosed || stopped != null) return;
                    LOGGER.warn("Model lease heartbeat stopped for task {}: {}",
                            lease.id(), unconfirmed.getClass().getSimpleName());
                    stop(new Stopped("Lease heartbeat could not be confirmed", unconfirmed));
                }
            }
        }

        /** Called only under lifecycle; cancellation wakes waiters, but need not stop a provider. */
        private void stop(Stopped reason) {
            if (scopeClosed || stopped != null) return;
            stopped = reason;
            if (heartbeat != null) heartbeat.cancel(false);
            if (call != null) cancelCall(call);
        }

        private void cancelCall(FutureTask<?> future) {
            future.cancel(true);
            // ThreadPoolExecutor does not remove a canceled queued FutureTask automatically.
            models.remove(future);
            if (call == future) call = null;
        }

        @Override
        public void close() {
            synchronized (lifecycle) {
                if (scopeClosed) return;
                scopeClosed = true;
                executions.remove(this);
                if (heartbeat != null) heartbeat.cancel(false);
                if (call != null) cancelCall(call);
            }
        }
    }
}
