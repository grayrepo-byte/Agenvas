package dev.agenvas.subtitle.application;

import dev.agenvas.shared.lifecycle.ShutdownGate;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dedicated single execution slot; the shared scheduler never blocks on ASR or queues native jobs. */
@Component
@ConditionalOnExpression("${agenvas.subtitles.worker-enabled:true} && !${agenvas.recovery-mode:false}")
public class SubtitleWorker {
    private final SubtitleRecognitionService jobs;
    private final ShutdownGate shutdown;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final String workerId = "subtitle-" + UUID.randomUUID();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("subtitle-asr").factory());
    public SubtitleWorker(SubtitleRecognitionService jobs, ShutdownGate shutdown) { this.jobs = jobs; this.shutdown = shutdown; }
    @Scheduled(fixedDelayString = "${agenvas.subtitles.poll-interval-ms:1000}") public void tick() {
        if (shutdown.isClosing() || !busy.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { if (!shutdown.isClosing()) jobs.processNext(workerId); }
                catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(SubtitleWorker.class).warn("Subtitle worker stopped ({})", failure.getClass().getSimpleName());
                } finally { busy.set(false); }
            });
        } catch (RejectedExecutionException stopped) { busy.set(false); }
    }
    @PreDestroy public void close() {
        worker.shutdownNow();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
