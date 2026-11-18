package dev.agenvas.skill.application;

import dev.agenvas.shared.lifecycle.BoundedWorkerExecutor;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import jakarta.annotation.PreDestroy;
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
    private final SkillService skills;
    private final SkillRunService runSkills;
    private final BoundedWorkerExecutor executor;

    public SkillWorker(SkillService skills, ShutdownGate shutdown, SkillRunService runSkills) {
        this.skills = skills;
        this.runSkills = runSkills;
        this.executor = new BoundedWorkerExecutor(shutdown, "skill-copy", LOGGER,
                "Skill worker pass failed: {}");
    }

    @Scheduled(fixedDelayString = "${agenvas.skill.poll-interval-ms:500}")
    public void tick() {
        executor.execute(() -> {
            skills.cleanupNext();
            runSkills.cleanupNext();
            skills.processNext();
            runSkills.processNext();
        });
    }

    @PreDestroy
    public void close() {
        executor.close();
    }
}
