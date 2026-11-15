package dev.agenvas.provider.application;

import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import jakarta.annotation.PreDestroy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** A bounded timer owns lease renewal while media submit, polling and byte archival block on I/O. */
@Component
public final class MediaLeaseHeartbeat implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaLeaseHeartbeat.class);
    // Matches the scheduler's three submit slots and one poll/archive slot; never queue unbounded timers.
    private static final int MAX_ACTIVE_EXECUTIONS = 4;
    private final TaskService tasks;
    private final long intervalMillis;
    private final ScheduledThreadPoolExecutor timers;
    private final Object lifecycle = new Object();
    private final Set<Scope> scopes = new HashSet<>();
    private boolean closed;

    @Autowired
    public MediaLeaseHeartbeat(TaskService tasks, TaskProperties properties) {
        this(tasks, properties, new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("media-lease-heartbeat").factory()));
    }

    MediaLeaseHeartbeat(TaskService tasks, TaskProperties properties, ScheduledThreadPoolExecutor timers) {
        this.tasks = tasks;
        this.intervalMillis = Math.max(1, properties.leaseDuration().toMillis() / 3);
        this.timers = timers;
        timers.setRemoveOnCancelPolicy(true);
    }

    Scope open(Task lease, String workerId) {
        synchronized (lifecycle) {
            if (closed || scopes.size() >= MAX_ACTIVE_EXECUTIONS)
                throw new LeaseLost("Media lease heartbeat is unavailable");
            Scope scope = new Scope(lease, workerId);
            scopes.add(scope);
            try {
                scope.heartbeat = timers.scheduleWithFixedDelay(scope::renew, intervalMillis, intervalMillis,
                        TimeUnit.MILLISECONDS);
                return scope;
            } catch (RuntimeException unavailable) {
                scope.close();
                throw new LeaseLost("Media lease heartbeat could not start", unavailable);
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (lifecycle) {
            if (closed)
                return;
            closed = true;
            for (Scope scope : scopes)
                scope.stop(new LeaseLost("Media lease heartbeat is closed"));
            scopes.clear();
        }
        timers.shutdownNow();
    }

    /** Local lease loss is not a provider rejection, cancellation acknowledgement or retry permission. */
    static final class LeaseLost extends RuntimeException {
        LeaseLost(String message) {
            super(message);
        }

        LeaseLost(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final class Scope implements AutoCloseable {
        private final Task lease;
        private final String workerId;
        private ScheduledFuture<?> heartbeat;
        private LeaseLost stopped;
        private boolean scopeClosed;

        private Scope(Task lease, String workerId) {
            this.lease = lease;
            this.workerId = workerId;
        }

        void checkActive() {
            synchronized (lifecycle) {
                if (stopped != null)
                    throw stopped;
                if (scopeClosed)
                    throw new LeaseLost("Media lease scope is closed");
            }
        }

        private void renew() {
            synchronized (lifecycle) {
                if (scopeClosed || stopped != null)
                    return;
            }
            try {
                // This separate media predicate intentionally keeps accepted canceled work renewable.
                tasks.heartbeatMediaLease(lease.id(), workerId, lease.leaseEpoch());
            } catch (RuntimeException lost) {
                synchronized (lifecycle) {
                    if (!scopeClosed && stopped == null) {
                        stop(new LeaseLost("Media task lease could not be confirmed", lost));
                        LOGGER.warn("Media lease renewal stopped: {}", lost.getClass().getSimpleName());
                    }
                }
            }
        }

        private void stop(LeaseLost cause) {
            stopped = cause;
            if (heartbeat != null)
                heartbeat.cancel(false);
        }

        @Override
        public void close() {
            synchronized (lifecycle) {
                if (scopeClosed)
                    return;
                scopeClosed = true;
                if (heartbeat != null)
                    heartbeat.cancel(false);
                scopes.remove(this);
            }
        }
    }
}
