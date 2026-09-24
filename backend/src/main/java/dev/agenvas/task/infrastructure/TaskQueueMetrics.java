package dev.agenvas.task.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes a read-only, bounded-cardinality snapshot of durable task states and queue age. */
@Component
public class TaskQueueMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskQueueMetrics.class);
    private final Supplier<Snapshot> snapshotLoader;
    private final AtomicLong ready = new AtomicLong(-1);
    private final AtomicLong unknown = new AtomicLong(-1);
    private final AtomicLong blocked = new AtomicLong(-1);
    private final AtomicReference<Double> oldestReadyAgeSeconds = new AtomicReference<>(-1.0);
    private final AtomicBoolean databaseUnavailable = new AtomicBoolean();

    @Autowired
    public TaskQueueMetrics(JdbcClient jdbc, MeterRegistry meters) {
        this(() -> jdbc.sql("""
                        select count(*) filter (where status = 'READY') as ready,
                               count(*) filter (where status = 'UNKNOWN') as unknown,
                               count(*) filter (where status = 'BLOCKED') as blocked,
                               coalesce(max(greatest(0, extract(epoch from
                                   (now() - updated_at)))) filter (where status = 'READY'
                                   and next_action_at <= now()), 0)::double precision
                                   as oldest_ready_age_seconds
                        from task
                        where status in ('READY', 'UNKNOWN', 'BLOCKED')
                        """)
                .query((row, ignored) -> new Snapshot(row.getLong("ready"),
                        row.getLong("unknown"), row.getLong("blocked"),
                        row.getDouble("oldest_ready_age_seconds"))).single(), meters);
    }

    /** A deterministic loader keeps DB failure and recovery observable in a unit test. */
    TaskQueueMetrics(Supplier<Snapshot> snapshotLoader, MeterRegistry meters) {
        this.snapshotLoader = snapshotLoader;
        register(meters, "READY", ready);
        register(meters, "UNKNOWN", unknown);
        register(meters, "BLOCKED", blocked);
        Gauge.builder("agenvas.tasks.ready.oldest.age.seconds", oldestReadyAgeSeconds,
                        AtomicReference::get)
                .description("Oldest due READY task age since last state update; -1 when unavailable")
                .register(meters);
    }

    /** A negative value means the first snapshot has not completed or the DB is unavailable. */
    private void register(MeterRegistry meters, String status, AtomicLong value) {
        Gauge.builder("agenvas.tasks.current", value, AtomicLong::get)
                .description("Durable tasks in one allowlisted state; -1 when unavailable")
                .tag("status", status)
                .register(meters);
    }

    /** Refreshes all three values from one database statement without claiming work. */
    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        try {
            Snapshot snapshot = snapshotLoader.get();
            ready.set(snapshot.ready());
            unknown.set(snapshot.unknown());
            blocked.set(snapshot.blocked());
            oldestReadyAgeSeconds.set(snapshot.oldestReadyAgeSeconds());
            if (databaseUnavailable.getAndSet(false)) {
                LOGGER.info("Task queue metrics database snapshot recovered");
            }
        } catch (DataAccessException unavailable) {
            ready.set(-1);
            unknown.set(-1);
            blocked.set(-1);
            oldestReadyAgeSeconds.set(-1.0);
            if (databaseUnavailable.compareAndSet(false, true)) {
                LOGGER.warn("Task queue metrics database snapshot unavailable",
                        unavailable);
            }
        }
    }

    /** One query result keeps count and age from the same committed database snapshot. */
    record Snapshot(long ready, long unknown, long blocked, double oldestReadyAgeSeconds) {}
}
