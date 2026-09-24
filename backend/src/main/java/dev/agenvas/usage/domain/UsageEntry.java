package dev.agenvas.usage.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Immutable reservation or settlement; null amounts mean an unknown cost, not zero. */
public record UsageEntry(UUID id, UUID projectId, UUID runId, UUID taskId,
        String operationKey, EntryType entryType, JsonNode quantity,
        BigDecimal estimatedCost, BigDecimal actualCost, String currency,
        CostStatus costStatus, String costSource, Integer providerConfigVersion,
        String workflowVersion, String modelId, Instant createdAt) {

    /** One append-only accounting action. */
    public enum EntryType { RESERVATION, SETTLEMENT, RELEASE }

    /** Cost knowledge is independent of whether any quantity was consumed. */
    public enum CostStatus { KNOWN, ESTIMATED, UNKNOWN }
}
