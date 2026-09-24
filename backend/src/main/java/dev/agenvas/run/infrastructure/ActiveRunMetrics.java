package dev.agenvas.run.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes the durable, non-terminal Run count without project or user labels. */
@Component
public class ActiveRunMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActiveRunMetrics.class);
    private final LongSupplier countLoader;
    private final AtomicLong active = new AtomicLong(-1);
    private final AtomicBoolean databaseUnavailable = new AtomicBoolean();

    @Autowired
    public ActiveRunMetrics(JdbcClient jdbc, MeterRegistry meters) {
        this(() -> jdbc.sql("""
                        select count(*) from agent_run
                        where status not in ('CANCELED', 'FAILED', 'SUCCEEDED')
                        """).query(Long.class).single(), meters);
    }

    /** An injectable loader makes unavailable and recovered snapshots deterministic in tests. */
    ActiveRunMetrics(LongSupplier countLoader, MeterRegistry meters) {
        this.countLoader = countLoader;
        Gauge.builder("agenvas.runs.active", active, AtomicLong::get)
                .description("Non-terminal Agent Runs; -1 when database snapshot unavailable")
                .register(meters);
    }

    /** Refreshes one read-only database snapshot without scheduling or advancing a Run. */
    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        try {
            active.set(countLoader.getAsLong());
            if (databaseUnavailable.getAndSet(false)) {
                LOGGER.info("Active Run metrics database snapshot recovered");
            }
        } catch (DataAccessException unavailable) {
            active.set(-1);
            if (databaseUnavailable.compareAndSet(false, true)) {
                LOGGER.warn("Active Run metrics database snapshot unavailable", unavailable);
            }
        }
    }
}
