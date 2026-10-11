package dev.agenvas.provider.domain;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Public input semantics for fixed cloud protocols; no graph targets, credentials or external URLs. */
public record CreatorInputField(String key, String label, String description, RunningHubDefinition.FieldType type,
        boolean required, JsonNode defaultValue, BigDecimal minimum, BigDecimal maximum,
        Integer maxLength, List<RunningHubDefinition.Option> options, boolean advanced,
        RunningHubDefinition.Source source, RunningHubDefinition.Condition enabledWhen) {
    public boolean media() { return type == RunningHubDefinition.FieldType.IMAGE
            || type == RunningHubDefinition.FieldType.VIDEO || type == RunningHubDefinition.FieldType.AUDIO; }
    public RunningHubDefinition.Source effectiveSource() { return source == null ? RunningHubDefinition.Source.PARAMETER : source; }
}
