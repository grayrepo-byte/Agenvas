package dev.agenvas.llm.application;

import dev.agenvas.run.domain.AgentRun;
import java.util.List;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;

/** Frozen availability and committed activation are separate: selection never implies use. */
final class RunSkills {
    private RunSkills() {}
    static List<JsonNode> available(AgentRun run) {
        JsonNode selected = run.contextSnapshot().path("creativeSkills");
        if (selected.isArray()) return StreamSupport.stream(selected.spliterator(), false).toList();
        JsonNode historical = run.contextSnapshot().path("creativeSkill");
        return historical.isObject() ? List.of(historical) : List.of();
    }
    static List<JsonNode> activated(AgentRun run, List<JsonNode> reads) {
        if (run.policySnapshot().path("toolPolicyVersion").asInt(1) < RunToolPolicy.PROGRESSIVE_VERSION) return available(run);
        return available(run).stream().filter(skill -> reads.stream().anyMatch(read ->
                "SKILL.md".equals(read.path("path").asText())
                && skill.path("skillVersionId").asText().equals(read.path("skillVersionId").asText()))).toList();
    }
}
