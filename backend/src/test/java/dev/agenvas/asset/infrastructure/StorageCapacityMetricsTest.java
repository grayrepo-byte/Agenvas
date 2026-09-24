package dev.agenvas.asset.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.asset.application.AssetProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Disk alerts must not mistake an unavailable volume for the last healthy sample. */
class StorageCapacityMetricsTest {

    @TempDir Path archiveParent;

    @Test
    void realFilesystemSampleUsesTheNearestExistingArchiveParent() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            StorageCapacityMetrics metrics = new StorageCapacityMetrics(
                    new AssetProperties(archiveParent.resolve("not-created").resolve("assets")),
                    meters);
            metrics.refresh();
            assertThat(total(meters)).isPositive();
            assertThat(usable(meters)).isBetween(0.0, total(meters));
            assertThat(ratio(meters)).isBetween(0.0, 1.0);
        } finally {
            meters.close();
        }
    }

    @Test
    void capacityFailureBecomesUnknownAndRecoveryKeepsMetricsUnlabeled() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            AtomicReference<StorageCapacityMetrics.Space> value = new AtomicReference<>(
                    new StorageCapacityMetrics.Space(1_000, 250));
            AtomicBoolean fail = new AtomicBoolean();
            StorageCapacityMetrics metrics = new StorageCapacityMetrics(() -> {
                if (fail.get()) throw new IOException("Injected volume outage");
                return value.get();
            }, meters);

            assertThat(ratio(meters)).isEqualTo(-1);
            metrics.refresh();
            assertThat(total(meters)).isEqualTo(1_000);
            assertThat(usable(meters)).isEqualTo(250);
            assertThat(ratio(meters)).isEqualTo(0.75);

            fail.set(true);
            metrics.refresh();
            assertThat(total(meters)).isEqualTo(-1);
            assertThat(usable(meters)).isEqualTo(-1);
            assertThat(ratio(meters)).isEqualTo(-1);

            fail.set(false);
            value.set(new StorageCapacityMetrics.Space(2_000, 1_000));
            metrics.refresh();
            assertThat(total(meters)).isEqualTo(2_000);
            assertThat(usable(meters)).isEqualTo(1_000);
            assertThat(ratio(meters)).isEqualTo(0.5);
            assertThat(meters.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).isEmpty());
        } finally {
            meters.close();
        }
    }

    @Test
    void invalidCapacityCannotLookLikeHealthyFreeSpace() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            StorageCapacityMetrics metrics = new StorageCapacityMetrics(
                    () -> new StorageCapacityMetrics.Space(0, 0), meters);
            metrics.refresh();
            assertThat(ratio(meters)).isEqualTo(-1);
        } finally {
            meters.close();
        }
    }

    private double total(SimpleMeterRegistry meters) {
        return meters.get("agenvas.storage.disk.total.bytes").gauge().value();
    }

    private double usable(SimpleMeterRegistry meters) {
        return meters.get("agenvas.storage.disk.usable.bytes").gauge().value();
    }

    private double ratio(SimpleMeterRegistry meters) {
        return meters.get("agenvas.storage.disk.used.ratio").gauge().value();
    }
}
