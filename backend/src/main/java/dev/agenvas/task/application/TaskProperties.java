package dev.agenvas.task.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded database scheduler settings. */
@ConfigurationProperties(prefix = "agenvas.task")
public record TaskProperties(Duration leaseDuration, int maxClaimBatch) {

    /** Supplies safe single-node defaults while remaining externally configurable. */
    public TaskProperties {
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
        maxClaimBatch = maxClaimBatch == 0 ? 16 : maxClaimBatch;
        if (leaseDuration.isNegative()
                || leaseDuration.isZero()
                || maxClaimBatch < 1
                || maxClaimBatch > 100) {
            throw new IllegalArgumentException("Invalid task lease or claim batch configuration");
        }
    }
}
