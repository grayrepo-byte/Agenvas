package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Model-facing schemas are observable public policy behavior, including old Run recovery. */
class RunToolPolicyTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ToolRegistry registry = new ToolRegistry();
    @Test void ordinaryReadToolsStayAvailableWithoutSkillResources() {
        assertThat(RunToolPolicy.current(false, false)).contains("read_project_summary", "read_selection", "read_artifacts")
                .doesNotContain("read_skill_resource");
        assertThat(RunToolPolicy.current(true, true)).contains("read_skill_resource");
    }
    @Test void historicalRunsKeepTheirOriginalTools() {
        assertThat(names(mapper.createObjectNode().put("systemPromptVersion",2)))
                .contains("create_text","read_artifacts")
                .doesNotContain("read_skill_resource","propose_media_generation");
        assertThat(names(mapper.createObjectNode().put("systemPromptVersion",3)))
                .contains("propose_media_generation").doesNotContain("read_skill_resource");
    }
    @Test void pinnedAllowlistIsSharedWithDefinitionsAndCallbacksCannotExecute() {
        var policy=mapper.createObjectNode().put("systemPromptVersion",4).put("toolPolicyVersion",1);
        policy.putArray("allowedTools").add("read_skill_resource");
        assertThat(names(policy)).containsExactly("read_skill_resource");
        assertThat(RunToolPolicy.allowed(policy)).containsExactly("read_skill_resource");
        assertThatThrownBy(() -> registry.modelDefinitions(policy).getFirst().call("{}"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void unsupportedOrUnpinnedPoliciesFailClosed() {
        var policy=mapper.createObjectNode().put("systemPromptVersion",4);
        assertThatThrownBy(() -> registry.modelDefinitions(policy)).isInstanceOf(IllegalStateException.class);
        policy.put("toolPolicyVersion",1).putArray("allowedTools").add("run_shell");
        assertThatThrownBy(() -> registry.modelDefinitions(policy)).isInstanceOf(IllegalStateException.class);
    }
    private List<String> names(tools.jackson.databind.JsonNode policy) {
        return registry.modelDefinitions(policy).stream().map(callback -> callback.getToolDefinition().name()).toList();
    }
}
