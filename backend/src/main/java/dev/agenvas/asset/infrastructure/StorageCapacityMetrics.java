package dev.agenvas.asset.infrastructure;

import dev.agenvas.asset.application.AssetProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Samples the filesystem holding the private asset volume without accessing media contents. */
@Component
public class StorageCapacityMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageCapacityMetrics.class);
    private static final Space UNAVAILABLE = new Space(-1, -1);

    private final SpaceReader reader;
    private final AtomicReference<Space> current = new AtomicReference<>(UNAVAILABLE);
    private final AtomicBoolean unavailable = new AtomicBoolean();

    @Autowired
    public StorageCapacityMetrics(AssetProperties properties, MeterRegistry meters) {
        this(() -> readFileStore(properties.root().toAbsolutePath().normalize()), meters);
    }

    /** Injectable reader makes filesystem outages and recovery deterministic in unit tests. */
    StorageCapacityMetrics(SpaceReader reader, MeterRegistry meters) {
        this.reader = reader;
        Gauge.builder("agenvas.storage.disk.total.bytes", current,
                        value -> value.get().totalBytes())
                .description("Asset-volume filesystem total bytes; -1 when unavailable")
                .register(meters);
        Gauge.builder("agenvas.storage.disk.usable.bytes", current,
                        value -> value.get().usableBytes())
                .description("Asset-volume filesystem usable bytes; -1 when unavailable")
                .register(meters);
        Gauge.builder("agenvas.storage.disk.used.ratio", current, value -> {
                    Space space = value.get();
                    return space.totalBytes() < 1 ? -1.0
                            : 1.0 - (double) space.usableBytes() / space.totalBytes();
                })
                .description("Asset-volume filesystem used ratio; -1 when unavailable")
                .register(meters);
    }

    /** Reads one consistent pair and never retains the last healthy value after failure. */
    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        try {
            Space space = reader.read();
            if (space.totalBytes() < 1 || space.usableBytes() < 0
                    || space.usableBytes() > space.totalBytes()) {
                throw new IOException("Invalid storage capacity snapshot");
            }
            current.set(space);
            if (unavailable.getAndSet(false)) {
                LOGGER.info("Asset-volume capacity snapshot recovered");
            }
        } catch (IOException | SecurityException failure) {
            current.set(UNAVAILABLE);
            if (unavailable.compareAndSet(false, true)) {
                LOGGER.warn("Asset-volume capacity snapshot unavailable", failure);
            }
        }
    }

    /** The archive root may not exist before its first write; use its nearest real parent. */
    private static Space readFileStore(Path root) throws IOException {
        Path candidate = root;
        while (candidate != null && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = candidate.getParent();
        }
        if (candidate == null || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(candidate)) {
            throw new IOException("Asset-volume directory unavailable");
        }
        var store = Files.getFileStore(candidate);
        return new Space(store.getTotalSpace(), store.getUsableSpace());
    }

    /** Both values originate from the same filesystem sample. */
    record Space(long totalBytes, long usableBytes) {}

    @FunctionalInterface
    interface SpaceReader {
        Space read() throws IOException;
    }
}
