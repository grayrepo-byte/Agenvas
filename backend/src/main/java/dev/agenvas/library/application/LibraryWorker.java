package dev.agenvas.library.application;

import dev.agenvas.shared.lifecycle.ShutdownGate;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A single local copy executor, with no queued jobs or blocked shared scheduler threads. */
@Component
@ConditionalOnProperty(name = "agenvas.library.worker-enabled", havingValue = "true", matchIfMissing = true)
public class LibraryWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(LibraryWorker.class);
    private static final int SHUTDOWN_SECONDS = 10;
    private final LibraryService library;
    private final ShutdownGate shutdown;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("library-copy").factory());
    private final AtomicBoolean busy = new AtomicBoolean();
    public LibraryWorker(LibraryService library, ShutdownGate shutdown) { this.library = library; this.shutdown = shutdown; }
    @Scheduled(fixedDelayString = "${agenvas.library.poll-interval-ms:500}")
    public void tick() {
        if (shutdown.isClosing() || !busy.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    if (!shutdown.isClosing()) { library.cleanupNext(); library.processNext(); }
                } catch (RuntimeException failure) {
                    // A failed DB claim leaves its command recoverable by the next tick.
                    LOGGER.warn("Library worker pass failed: {}", failure.getClass().getSimpleName());
                } finally { busy.set(false); }
            });
        } catch (RejectedExecutionException stopped) {
            busy.set(false);
        }
    }
    @PreDestroy public void close() {
        executor.shutdown();
        try { if (!executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException interrupted) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
