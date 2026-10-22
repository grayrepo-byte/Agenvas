package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Model definitions and durable execution share one explicit current allowlist. */
class RunToolPolicyTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ToolRegistry registry = new ToolRegistry();
    @Test void ordinaryReadToolsStayAvailableWithoutSkillResources() {
        assertThat(RunToolPolicy.current(false, false)).contains("read_project_summary", "read_selection", "read_artifacts")
                .doesNotContain("read_skill", "read_skill_resource", "read_skill_asset");
        assertThat(RunToolPolicy.current(true, true, true)).contains("read_skill", "read_skill_resource", "read_skill_asset");
    }
    @Test void retiredAndMissingPoliciesFailClosed() {
        var policy = mapper.createObjectNode().put("schemaVersion", RunToolPolicy.CURRENT_SCHEMA_VERSION)
                .put("systemPromptVersion", InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION);
        policy.putArray("allowedTools").add("read_artifacts");
        assertThatThrownBy(() -> registry.modelDefinitions(policy)).isInstanceOf(IllegalStateException.class);
        for (int version = 1; version < RunToolPolicy.CURRENT_VERSION; version++) {
            policy.put("toolPolicyVersion", version);
            assertThatThrownBy(() -> registry.modelDefinitions(policy)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void mismatchedSnapshotAndPromptProtocolsCannotReuseCurrentToolPermissions() {
        var policy = policy();
        policy.putArray("allowedTools").add("read_artifacts");
        policy.put("schemaVersion", RunToolPolicy.CURRENT_SCHEMA_VERSION - 1);
        assertThatThrownBy(() -> registry.modelDefinitions(policy)).isInstanceOf(IllegalStateException.class);
        policy.put("schemaVersion", RunToolPolicy.CURRENT_SCHEMA_VERSION)
                .put("systemPromptVersion", InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION - 1);
        assertThatThrownBy(() -> registry.modelDefinitions(policy)).isInstanceOf(IllegalStateException.class);
    }
    @Test void pinnedAllowlistIsSharedWithDefinitionsAndCallbacksCannotExecute() {
        var policy = policy();
        policy.putArray("allowedTools").add("read_skill_resource");
        assertThat(names(policy)).containsExactly("read_skill_resource");
        assertThat(RunToolPolicy.allowed(policy)).containsExactly("read_skill_resource");
        assertThatThrownBy(() -> registry.modelDefinitions(policy).getFirst().call("{}"))
                .isInstanceOf(IllegalStateException.class);
        policy.withArray("allowedTools").add("read_skill_resource");
        assertThatThrownBy(() -> registry.modelDefinitions(policy)).isInstanceOf(IllegalStateException.class);
        policy.putArray("allowedTools").add("run_shell");
        assertThatThrownBy(() -> registry.modelDefinitions(policy)).isInstanceOf(IllegalStateException.class);
    }
    @Test void currentSchemasHaveCanvasTargetsAndNoAgentOutputGroups() {
        var policy = policy();
        policy.set("allowedTools", mapper.valueToTree(RunToolPolicy.CURRENT));
        assertThat(names(policy)).contains("read_canvas", "manage_canvas_node", "update_media_draft")
                .doesNotContain("place_artifacts");
        for (var callback : registry.modelDefinitions(policy)) {
            var definition = callback.getToolDefinition();
            assertThat(mapper.readTree(definition.inputSchema()).path("type").asText()).isEqualTo("object");
            assertThat(definition.inputSchema()).doesNotContain("AGENT_OUTPUT");
            assertThat(definition.description()).doesNotContain("frozenSelectedVersionId", "bound to this Run");
            if (definition.name().equals("propose_media_generation")) assertThat(definition.inputSchema()).contains("canvasItemId", "expectedDraftVersion", "expectedItemVersion");
            if (definition.name().equals("read_skill_resource")) assertThat(definition.inputSchema()).contains("skillVersionId");
        }
    }
    @Test void compositionSchemaUsesExactInputsAndDoesNotGrantExistingRunsNewPermissions() {
        var policy = policy();
        policy.putArray("allowedTools").add("read_artifacts");
        assertThat(names(policy)).doesNotContain("compose_video");
        policy.withArray("allowedTools").add("compose_video");
        var definition = registry.modelDefinitions(policy).stream()
                .map(callback -> callback.getToolDefinition()).filter(tool -> tool.name().equals("compose_video"))
                .findFirst().orElseThrow();
        var schema = mapper.readTree(definition.inputSchema());
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/properties/title/maxLength").asInt()).isEqualTo(160);
        assertThat(schema.at("/properties/shots/maxItems").asInt()).isEqualTo(12);
        assertThat(schema.at("/properties/shots/items/properties/lines/items/required").toString())
                .contains("audioVersionId").doesNotContain("startMs");
        assertThat(definition.inputSchema()).contains("videoVersionId", "audioVersionId", "rationale", "BACKGROUND")
                .doesNotContain("ownerId", "projectId", "runId", "url", "ffmpeg");
        assertThat(definition.description()).contains("queuing only", "do not poll", "1.15x");
    }
    private tools.jackson.databind.node.ObjectNode policy() {
        return mapper.createObjectNode().put("schemaVersion", RunToolPolicy.CURRENT_SCHEMA_VERSION)
                .put("systemPromptVersion", InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION)
                .put("toolPolicyVersion", RunToolPolicy.CURRENT_VERSION);
    }
    private List<String> names(JsonNode policy) {
        return registry.modelDefinitions(policy).stream().map(callback -> callback.getToolDefinition().name()).toList();
    }
}
