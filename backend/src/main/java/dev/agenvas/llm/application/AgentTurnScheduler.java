package dev.agenvas.llm.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls durable Agent tasks only when an operator enables a configured model runtime. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas.llm", name = "scheduler-enabled", havingValue = "true")
public class AgentTurnScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentTurnScheduler.class);
    private final AgentTurnWorker worker;
    private final String workerId = "agent-turn-" + UUID.randomUUID();

    public AgentTurnScheduler(AgentTurnWorker worker) {
        this.worker = worker;
    }

    /** One bounded claim per tick; the Task table is the only durable queue. */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        try {
            worker.runOnce(workerId);
        } catch (RuntimeException failure) {
            LOGGER.error("Agent-turn scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
