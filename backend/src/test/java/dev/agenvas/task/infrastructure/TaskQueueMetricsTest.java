package dev.agenvas.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
            AtomicReference<TaskQueueMetrics.Snapshot> snapshot = new AtomicReference<>(
                    new TaskQueueMetrics.Snapshot(3, 2, 1, 12.5));
            AtomicBoolean fail = new AtomicBoolean();
            TaskQueueMetrics queue = new TaskQueueMetrics(() -> {
                if (fail.get()) {
                    throw new DataAccessResourceFailureException("Injected database outage");
                }
                return snapshot.get();
            }, meters);
            assertThat(value(meters, "READY")).isEqualTo(-1);
            queue.refresh();
            assertThat(value(meters, "READY")).isEqualTo(3);
            assertThat(value(meters, "UNKNOWN")).isEqualTo(2);
            assertThat(value(meters, "BLOCKED")).isEqualTo(1);
            assertThat(oldestAge(meters)).isEqualTo(12.5);

            fail.set(true);
            queue.refresh();
            assertThat(value(meters, "READY")).isEqualTo(-1);
            assertThat(value(meters, "UNKNOWN")).isEqualTo(-1);
            assertThat(value(meters, "BLOCKED")).isEqualTo(-1);
            assertThat(oldestAge(meters)).isEqualTo(-1);

            snapshot.set(new TaskQueueMetrics.Snapshot(1, 0, 0, 0));
            fail.set(false);
            queue.refresh();
            assertThat(value(meters, "READY")).isEqualTo(1);
            assertThat(value(meters, "UNKNOWN")).isZero();
            assertThat(value(meters, "BLOCKED")).isZero();
            assertThat(oldestAge(meters)).isZero();
            assertThat(meters.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).allSatisfy(tag ->
                            assertThat(tag.getKey()).isEqualTo("status")));
        } finally {
            meters.close();
        }
    }

    /** Reads the same observable value exposed by the authenticated Actuator endpoint. */
    private double value(SimpleMeterRegistry meters, String status) {
        return meters.get("agenvas.tasks.current").tag("status", status).gauge().value();
    }

    /** Queue age has no project, run or task identifier label. */
    private double oldestAge(SimpleMeterRegistry meters) {
        return meters.get("agenvas.tasks.ready.oldest.age.seconds").gauge().value();
    }
}
