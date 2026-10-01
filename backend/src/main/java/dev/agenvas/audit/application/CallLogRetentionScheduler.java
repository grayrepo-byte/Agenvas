package dev.agenvas.audit.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "agenvas.audit", name = "retention-cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class CallLogRetentionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CallLogRetentionScheduler.class);
    private final CallLogRetentionService retention;
    public CallLogRetentionScheduler(CallLogRetentionService retention) { this.retention = retention; }

    @Scheduled(initialDelayString = "${agenvas.audit.retention-cleanup-initial-delay:PT1M}",
            fixedDelayString = "${agenvas.audit.retention-cleanup-interval:PT5M}")
    public void cleanExpired() {
        try {
            int deleted = retention.cleanExpired();
            if (deleted > 0) LOGGER.info("Expired execution histories removed count={}", deleted);
        } catch (RuntimeException failure) {
            // No request bodies or exception messages enter ordinary server logs.
            LOGGER.error("Call log retention cleanup failed code=CALL_LOG_CLEANUP_FAILED");
        }
    }
}
