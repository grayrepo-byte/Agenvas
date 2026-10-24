package dev.agenvas.llm.application;

import java.time.Duration;

/** Global director budgets. Checkpointed requests and active recovery/repair limits stay immutable. */
public record DirectorSettings(int contextWindowTokens, int compactionThresholdPercent,
        int responseReserveTokens, int summaryTokens, int recentToolRounds,
        int recoveryWindowMinutes, int maxModelRetries, int retryMaxDelaySeconds,
        int maxRepairAttempts, long version) {
    public DirectorSettings {
        if (contextWindowTokens < 8192 || contextWindowTokens > 2_000_000
                || compactionThresholdPercent < 1 || compactionThresholdPercent > 100
                || responseReserveTokens < 1024 || responseReserveTokens >= contextWindowTokens / 2
                || summaryTokens < 256 || summaryTokens > (contextWindowTokens - responseReserveTokens) / 4
                || recentToolRounds < 0 || recentToolRounds > 16
                || recoveryWindowMinutes < 1 || recoveryWindowMinutes > 10_080
                || maxModelRetries < 1 || maxModelRetries > 10_000
                || retryMaxDelaySeconds < 2 || retryMaxDelaySeconds > 3600
                || maxRepairAttempts < 1 || maxRepairAttempts > 10 || version < 1) {
            throw new IllegalArgumentException("Invalid director context or recovery budget");
        }
    }

    public static DirectorSettings defaults() {
        return new DirectorSettings(1_000_000, 50, 8192, 2048, 4, 1440, 288, 300, 4, 1);
    }

    Duration recoveryWindow() { return Duration.ofMinutes(recoveryWindowMinutes); }
    Duration retryMaxDelay() { return Duration.ofSeconds(retryMaxDelaySeconds); }
    int inputBudget() { return contextWindowTokens - responseReserveTokens; }

    /** The soft trigger uses the whole model window; response space is a separate hard bound. */
    int compactionTriggerTokens() {
        return (int) Math.min(inputBudget(), (long) contextWindowTokens * compactionThresholdPercent / 100);
    }
}
