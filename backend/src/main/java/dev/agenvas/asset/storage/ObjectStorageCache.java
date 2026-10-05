package dev.agenvas.asset.storage;

import dev.agenvas.asset.application.AssetProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Evicts idle processing copies; browser reads never fill this cache. Original local assets are excluded. */
@Component
public class ObjectStorageCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObjectStorageCache.class);
    private static final int LOCK_STRIPES = 64;
    private static final Object[] LOCKS = new Object[LOCK_STRIPES];
    private static final Duration MINIMUM_RETENTION = Duration.ofHours(1);
    static { java.util.Arrays.setAll(LOCKS, ignored -> new Object()); }
    private final Path root;
    private final Clock clock;
    private final Duration retention;
    public ObjectStorageCache(AssetProperties storage, Clock clock,
            @Value("${agenvas.storage.cache-retention:PT24H}") Duration retention) {
        if (retention.compareTo(MINIMUM_RETENTION) < 0) throw new IllegalArgumentException("Processing cache retention must be at least one hour");
        this.root = storage.root().toAbsolutePath().normalize().resolve(".cloud-cache");
        this.clock = clock; this.retention = retention;
    }
    static Object lock(Path path) { return LOCKS[Math.floorMod(path.toAbsolutePath().normalize().hashCode(), LOCKS.length)]; }
    @Scheduled(fixedDelayString = "${agenvas.storage.cache-cleanup-interval:PT1H}")
    public void cleanup() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        Instant cutoff = clock.instant().minus(retention);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    synchronized (lock(file)) {
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                && Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff))
                            Files.deleteIfExists(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) { LOGGER.warn("Object storage processing cache cleanup failed"); }
    }
}
