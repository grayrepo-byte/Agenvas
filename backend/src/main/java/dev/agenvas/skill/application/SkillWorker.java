package dev.agenvas.skill.application;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single bounded local archival worker; scheduling never blocks on media decoding or file copies. */
@Component
@ConditionalOnProperty(name = "agenvas.skill.worker-enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("!${agenvas.recovery-mode:false}")
public final class SkillWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillWorker.class);
    private static final int SHUTDOWN_SECONDS = 10;
    private final SkillService skills;
    private final ShutdownGate shutdown;
    private final SkillRunService runSkills;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("skill-copy").factory());
    private final AtomicBoolean busy = new AtomicBoolean();
    public SkillWorker(SkillService skills, ShutdownGate shutdown, SkillRunService runSkills) { this.skills = skills; this.shutdown = shutdown; this.runSkills = runSkills; }
    @Scheduled(fixedDelayString = "${agenvas.skill.poll-interval-ms:500}") public void tick() {
        if (shutdown.isClosing() || !busy.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { if (!shutdown.isClosing()) { skills.cleanupNext(); runSkills.cleanupNext(); skills.processNext(); runSkills.processNext(); } }
                catch (RuntimeException failure) { LOGGER.warn("Skill worker pass failed: {}", failure.getClass().getSimpleName()); }
                finally { busy.set(false); }
            });
        } catch (RejectedExecutionException stopped) { busy.set(false); }
    }
    @PreDestroy public void close() {
        executor.shutdown();
        try { if (!executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException interrupted) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
