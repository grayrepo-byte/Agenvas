package dev.agenvas.provider.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Advances approved ComfyUI image Tasks independently of browser and model-turn lifetimes. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
@ConditionalOnProperty(prefix = "agenvas.provider.comfyui", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class ComfyUiImageScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComfyUiImageScheduler.class);
    private final ComfyUiImageWorker worker;
    private final String pollerId = "comfy-image-poll-" + UUID.randomUUID();
    private final String submitterId = "comfy-image-submit-" + UUID.randomUUID();

    public ComfyUiImageScheduler(ComfyUiImageWorker worker) {
        this.worker = worker;
    }

    /** Poll the saved request first; the DB gate only admits a new job after it completes. */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        try {
            worker.pollOnce(pollerId);
            worker.submitOnce(submitterId);
        } catch (RuntimeException failure) {
            LOGGER.error("ComfyUI image scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
