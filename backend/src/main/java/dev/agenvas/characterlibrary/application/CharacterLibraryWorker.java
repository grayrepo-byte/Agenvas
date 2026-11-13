package dev.agenvas.characterlibrary.application;

import dev.agenvas.shared.lifecycle.ShutdownGate;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One bounded local-copy worker; recovery mode never claims or cleans operations. */
@Component
@ConditionalOnExpression("${agenvas.character-library.worker-enabled:true} && !${agenvas.recovery-mode:false}")
public class CharacterLibraryWorker {
    private final CharacterLibraryService service;
    private final ShutdownGate shutdown;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors
            .newSingleThreadExecutor(Thread.ofPlatform().name("character-library-copy").factory());
    public CharacterLibraryWorker(CharacterLibraryService service, ShutdownGate shutdown) {
        this.service = service;
        this.shutdown = shutdown;
    }
    @Scheduled(fixedDelayString = "${agenvas.character-library.poll-interval-ms:500}")
    public void tick() {
        if (shutdown.isClosing() || !busy.compareAndSet(false, true))
            return;
        try {
            executor.execute(() -> {
                try {
                    if (!shutdown.isClosing()) {
                        service.cleanupNext();
                        service.processNext();
                    }
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Character library worker pass failed: {}",
                            failure.getClass().getSimpleName());
                } finally {
                    busy.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            busy.set(false);
        }
    }
    @PreDestroy
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS))
                executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
