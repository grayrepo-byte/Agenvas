package dev.agenvas.subtitle.runtime;

import dev.agenvas.shared.lifecycle.ShutdownGate;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One durable download claim and one native-free streaming worker per instance. */
@Component
@ConditionalOnExpression("${agenvas.subtitles.download-worker-enabled:true} && !${agenvas.recovery-mode:false}")
public class ManagedAsrWorker {
    private final ManagedAsrService service;
    private final ShutdownGate shutdown;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors
            .newSingleThreadExecutor(Thread.ofPlatform().name("asr-model-download").factory());
    private final String owner = "asr-download-" + UUID.randomUUID();
    public ManagedAsrWorker(ManagedAsrService service, ShutdownGate shutdown) {
        this.service = service;
        this.shutdown = shutdown;
    }
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (shutdown.isClosing() || !busy.compareAndSet(false, true))
            return;
        try {
            executor.execute(() -> {
                try {
                    if (!shutdown.isClosing())
                        service.runNext(owner);
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("ASR download worker stopped ({})",
                            failure.getClass().getSimpleName());
                } finally {
                    busy.set(false);
                }
            });
        } catch (RejectedExecutionException stopped) {
            busy.set(false);
        }
    }
    @PreDestroy
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }
}
