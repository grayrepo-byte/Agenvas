package dev.agenvas.llm.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded restart repair and deadline handling; never calls an LLM or resubmits generation. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode", havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas.llm", name = "scheduler-enabled", havingValue = "true")
public class AgentMediaRecoveryScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMediaRecoveryScheduler.class);
    private static final int BATCH_SIZE = 100;
    private final AgentMediaOutcomeService outcomes;
    private UUID cursor;

    public AgentMediaRecoveryScheduler(AgentMediaOutcomeService outcomes) { this.outcomes = outcomes; }

    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public synchronized void tick() {
        var candidates = outcomes.recoveryCandidates(cursor, BATCH_SIZE);
        for (var approval : candidates) {
            try {
                outcomes.reconcile(approval.ownerId(), approval.projectId(), approval.runId(), approval.id());
            } catch (RuntimeException failure) {
                LOGGER.warn("Media approval recovery failed: {}", failure.getClass().getSimpleName());
            }
            cursor = approval.id();
        }
        if (candidates.size() < BATCH_SIZE) cursor = null;
    }
}
