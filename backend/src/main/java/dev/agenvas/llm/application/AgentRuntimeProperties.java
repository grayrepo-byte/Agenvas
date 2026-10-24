package dev.agenvas.llm.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Deployment budgets for new requests; persisted requests and retry deadlines remain immutable. */
@ConfigurationProperties("agenvas.llm.agent")
public record AgentRuntimeProperties(
        @DefaultValue("1000000") int contextWindowTokens,
        @DefaultValue("50") int compactionThresholdPercent,
        @DefaultValue("8192") int responseReserveTokens,
        @DefaultValue("2048") int summaryTokens,
        @DefaultValue("4") int recentToolRounds,
        @DefaultValue("24h") Duration recoveryWindow,
        @DefaultValue("288") int maxModelRetries,
        @DefaultValue("5m") Duration retryMaxDelay,
        @DefaultValue("4") int maxRepairAttempts) {
    public AgentRuntimeProperties {
        if (contextWindowTokens < 8192 || contextWindowTokens > 2_000_000
                || compactionThresholdPercent < 1 || compactionThresholdPercent > 100
                || responseReserveTokens < 1024 || responseReserveTokens >= contextWindowTokens / 2
                || summaryTokens < 256 || summaryTokens > (contextWindowTokens - responseReserveTokens) / 4
                || recentToolRounds < 0 || recentToolRounds > 16
                || recoveryWindow == null || recoveryWindow.compareTo(Duration.ofMinutes(1)) < 0
                || recoveryWindow.compareTo(Duration.ofDays(7)) > 0
                || maxModelRetries < 1 || maxModelRetries > 10_000
                || retryMaxDelay == null || retryMaxDelay.compareTo(Duration.ofSeconds(2)) < 0
                || retryMaxDelay.compareTo(Duration.ofHours(1)) > 0
                || maxRepairAttempts < 1 || maxRepairAttempts > 10) {
            throw new IllegalArgumentException("Invalid Agent context or recovery budget");
        }
    }

    public static AgentRuntimeProperties defaults() {
        return new AgentRuntimeProperties(1_000_000, 50, 8_192, 2_048, 4,
                Duration.ofHours(24), 288, Duration.ofMinutes(5), 4);
    }

    int inputBudget() { return contextWindowTokens - responseReserveTokens; }

    /** The soft trigger uses the whole model window; response space remains a separate hard bound. */
    int compactionTriggerTokens() {
        return (int) Math.min(inputBudget(), (long) contextWindowTokens * compactionThresholdPercent / 100);
    }
}
