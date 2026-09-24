package dev.agenvas.task.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes a read-only, bounded-cardinality snapshot of durable task states. */
@Component
public class TaskQueueMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskQueueMetrics.class);
    private final Supplier<Map<String, Long>> countsLoader;
    private final AtomicLong ready = new AtomicLong(-1);
    private final AtomicLong unknown = new AtomicLong(-1);
    private final AtomicLong blocked = new AtomicLong(-1);
    private final AtomicBoolean databaseUnavailable = new AtomicBoolean();

    @Autowired
    public TaskQueueMetrics(JdbcClient jdbc, MeterRegistry meters) {
        this(() -> jdbc.sql("""
                        select status, count(*) as total from task
                        where status in ('READY', 'UNKNOWN', 'BLOCKED')
                        group by status
                        """)
                .query((row, ignored) -> Map.entry(row.getString("status"),
                        row.getLong("total")))
                .list().stream().collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue)), meters);
    }

    /** A deterministic loader keeps DB failure and recovery observable in a unit test. */
    TaskQueueMetrics(Supplier<Map<String, Long>> countsLoader, MeterRegistry meters) {
        this.countsLoader = countsLoader;
        register(meters, "READY", ready);
        register(meters, "UNKNOWN", unknown);
        register(meters, "BLOCKED", blocked);
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
            Map<String, Long> counts = countsLoader.get();
            ready.set(counts.getOrDefault("READY", 0L));
            unknown.set(counts.getOrDefault("UNKNOWN", 0L));
            blocked.set(counts.getOrDefault("BLOCKED", 0L));
            if (databaseUnavailable.getAndSet(false)) {
                LOGGER.info("Task queue metrics database snapshot recovered");
            }
        } catch (DataAccessException unavailable) {
            ready.set(-1);
            unknown.set(-1);
            blocked.set(-1);
            if (databaseUnavailable.compareAndSet(false, true)) {
                LOGGER.warn("Task queue metrics database snapshot unavailable",
                        unavailable);
            }
        }
    }
}
