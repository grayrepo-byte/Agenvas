package dev.agenvas.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** A failed database scrape must not present the last healthy queue size as current. */
class TaskQueueMetricsTest {

    @Test
    void unavailableSnapshotBecomesUnknownAndRecoversWithoutNewLabels() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            AtomicReference<Map<String, Long>> counts = new AtomicReference<>(Map.of(
                    "READY", 3L, "UNKNOWN", 2L, "BLOCKED", 1L));
            AtomicBoolean fail = new AtomicBoolean();
            TaskQueueMetrics queue = new TaskQueueMetrics(() -> {
                if (fail.get()) {
                    throw new DataAccessResourceFailureException("Injected database outage");
                }
                return counts.get();
            }, meters);
            assertThat(value(meters, "READY")).isEqualTo(-1);
            queue.refresh();
            assertThat(value(meters, "READY")).isEqualTo(3);
            assertThat(value(meters, "UNKNOWN")).isEqualTo(2);
            assertThat(value(meters, "BLOCKED")).isEqualTo(1);

            fail.set(true);
            queue.refresh();
            assertThat(value(meters, "READY")).isEqualTo(-1);
            assertThat(value(meters, "UNKNOWN")).isEqualTo(-1);
            assertThat(value(meters, "BLOCKED")).isEqualTo(-1);

            counts.set(Map.of("READY", 1L));
            fail.set(false);
            queue.refresh();
            assertThat(value(meters, "READY")).isEqualTo(1);
            assertThat(value(meters, "UNKNOWN")).isZero();
            assertThat(value(meters, "BLOCKED")).isZero();
            assertThat(meters.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).singleElement()
                            .satisfies(tag -> assertThat(tag.getKey()).isEqualTo("status")));
        } finally {
            meters.close();
        }
    }

    /** Reads the same observable value exposed by the authenticated Actuator endpoint. */
    private double value(SimpleMeterRegistry meters, String status) {
        return meters.get("agenvas.tasks.current").tag("status", status).gauge().value();
    }
}
