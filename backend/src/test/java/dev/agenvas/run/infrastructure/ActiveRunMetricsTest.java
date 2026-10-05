package dev.agenvas.run.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** A stale Run count must not appear current during a database outage. */
class ActiveRunMetricsTest {

    @Test
    void snapshotFailureAndRecoveryKeepMetricUnlabeled() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            AtomicLong count = new AtomicLong(2);
            AtomicBoolean fail = new AtomicBoolean();
            ActiveRunMetrics metrics = new ActiveRunMetrics(() -> {
                if (fail.get()) {
                    throw new DataAccessResourceFailureException("Injected database outage");
                }
                return count.get();
            }, meters);

            assertThat(value(meters)).isEqualTo(-1);
            metrics.refresh();
            assertThat(value(meters)).isEqualTo(2);
            fail.set(true);
            metrics.refresh();
            assertThat(value(meters)).isEqualTo(-1);
            count.set(0);
            fail.set(false);
            metrics.refresh();
            assertThat(value(meters)).isZero();
            assertThat(meters.get("agenvas.runs.active").gauge().getId().getTags())
                    .isEmpty();
        } finally {
            meters.close();
        }
    }

    /** Reads the same gauge value exposed through authenticated Actuator metrics. */
    private double value(SimpleMeterRegistry meters) {
        return meters.get("agenvas.runs.active").gauge().value();
    }
}
