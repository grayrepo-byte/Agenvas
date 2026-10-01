package dev.agenvas.audit.domain;

/** Null retentionDays preserves all call logs and execution ledgers until the administrator opts in. */
public record CallLogRetentionSettings(Integer retentionDays, int version) {
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 3650;
}
