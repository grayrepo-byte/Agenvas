package dev.agenvas.provider.application;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Independently advances only approved fixed-template ComfyUI video Tasks. */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
@ConditionalOnProperty(prefix = "agenvas.provider.comfyui.video", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class ComfyUiVideoScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComfyUiVideoScheduler.class);
    private final ComfyUiVideoPoller poller;
    private final ObjectProvider<ComfyUiVideoWorker> submitter;
    private final String pollerId = "comfy-video-poll-" + UUID.randomUUID();
    private final String submitterId = "comfy-video-submit-" + UUID.randomUUID();

    public ComfyUiVideoScheduler(ComfyUiVideoPoller poller,
            ObjectProvider<ComfyUiVideoWorker> submitter) {
        this.poller = poller;
        this.submitter = submitter;
    }

    /** Query the saved request before attempting another shared-slot submission. */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        try {
            poller.pollOnce(pollerId);
            ComfyUiVideoWorker enabledSubmitter = submitter.getIfAvailable();
            if (enabledSubmitter != null) enabledSubmitter.submitOnce(submitterId);
        } catch (RuntimeException failure) {
            LOGGER.error("ComfyUI video scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
