package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ProgressiveSkillTest {
    private final JsonMapper mapper = new JsonMapper();
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    private final ToolExecutionRepository ledger = mock(ToolExecutionRepository.class);
    private final ReadToolService reader = new ReadToolService(mock(ProjectService.class), mock(ArtifactService.class),
            mock(TaskService.class), mapper, ledger);

    @Test void firstRequestDisclosesOnlyNamesDescriptionsAndVersionIds() {
        AgentRun run = run(2);
        var snapshots = (ObjectNode) run.contextSnapshot();
        snapshots.put("projectName", "Project").put("agentName", "Agent").put("agentInstruction", "Create")
                .put("aspectRatio", "LANDSCAPE_16_9");
        snapshots.putArray("bindings").addObject().put("skillVersionId", first.toString())
                .put("artifactId", UUID.randomUUID().toString()).put("selectedVersionId", UUID.randomUUID().toString());
        when(run.instruction()).thenReturn("Create a poster");
        var runs = mock(AgentRunService.class);
        when(runs.get(any(), any(), any())).thenReturn(run);
        var capabilities = mock(MediaCapabilityService.class);
        when(capabilities.publishedCandidates()).thenReturn(List.of());
        var artifacts = mock(ArtifactService.class);
        var messages = new InitialModelContextService(runs, artifacts, capabilities).assemble(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String text = messages.stream().map(message -> message.getText()).reduce("", (a,b) -> a + b);
        assertThat(text).contains("name=first-skill", "name=second-skill", "description=First purpose", first.toString(), second.toString())
                .doesNotContain("PRIVATE_MAIN_FIRST", "PRIVATE_MAIN_SECOND", "references/shared.md", "😀甲😀乙", "SECOND_RESOURCE", "HIDDEN_REFERENCE_ALIAS");
        verifyNoInteractions(artifacts);
    }

    @Test void mainInstructionsActivateOnlyThroughCommittedLedgerAndAttachmentsAreVersionScoped() {
        AgentRun run = run(2);
        when(ledger.skillReads(any(), any())).thenReturn(List.of());
        assertInvalid(() -> reader.skillResource(run, UUID.randomUUID(), resourceArgs(first, 0, 2)));
        JsonNode main = reader.skill(run, UUID.randomUUID(), "{\"skillVersionId\":\"" + first + "\"}").path("data");
        assertThat(main.path("content").asText()).isEqualTo("PRIVATE_MAIN_FIRST");
        assertThat(main.path("resourceManifest").path(0).path("path").asText()).isEqualTo("references/shared.md");
        // An uncommitted read, including a rolled-back tool batch, never grants activation.
        assertInvalid(() -> reader.skillResource(run, UUID.randomUUID(), resourceArgs(first, 0, 2)));
        when(ledger.skillReads(any(), any())).thenReturn(List.of(main));
        JsonNode page = reader.skillResource(run, UUID.randomUUID(), resourceArgs(first, 1, 2)).path("data");
        assertThat(page.path("content").asText()).isEqualTo("甲😀");
        assertThat(page.path("nextOffset").asInt()).isEqualTo(3);
        assertThat(page.path("total").asInt()).isEqualTo(4);
        assertInvalid(() -> reader.skillResource(run, UUID.randomUUID(), resourceArgs(second, 0, 2)));
        assertInvalid(() -> reader.skill(run, UUID.randomUUID(), "{\"skillVersionId\":\"" + UUID.randomUUID() + "\"}"));
        JsonNode secondMain = reader.skill(run, UUID.randomUUID(), "{\"skillVersionId\":\"" + second + "\"}").path("data");
        when(ledger.skillReads(any(), any())).thenReturn(List.of(main, secondMain));
        assertThat(reader.skillResource(run, UUID.randomUUID(), resourceArgs(second, 0, 4000)).path("data").path("content").asText()).isEqualTo("SECOND_RESOURCE");
        assertInvalid(() -> reader.skillResource(run, UUID.randomUUID(), "{\"path\":\"references/shared.md\"}"));
        assertInvalid(() -> reader.skillResource(run, UUID.randomUUID(), "{\"skillVersionId\":\"" + first + "\",\"path\":\"../secret\"}"));
    }

    @Test void historicalSingleSkillResourcesKeepTheirOriginalArgumentsWithoutActivation() {
        AgentRun run = run(1);
        ObjectNode snapshot = (ObjectNode) run.contextSnapshot();
        snapshot.set("creativeSkill", snapshot.path("creativeSkills").path(0));
        snapshot.remove("creativeSkills");
        when(ledger.skillReads(any(), any())).thenReturn(List.of());
        assertThat(reader.skillResource(run, UUID.randomUUID(), "{\"path\":\"references/shared.md\"}")
                .path("data").path("content").asText()).isEqualTo("😀甲😀乙");
    }

    @Test void toolDefinitionsPinBothHistoricalAndProgressiveSchemas() {
        var registry = new ToolRegistry();
        ObjectNode policy = mapper.createObjectNode().put("systemPromptVersion", 7).put("toolPolicyVersion", 1);
        policy.putArray("allowedTools").add("read_skill_resource");
        String historical = registry.modelDefinitions(policy).getFirst().getToolDefinition().inputSchema();
        assertThat(mapper.readTree(historical).path("required")).hasSize(1);
        policy.put("toolPolicyVersion", 2).put("systemPromptVersion", 8);
        String current = registry.modelDefinitions(policy).getFirst().getToolDefinition().inputSchema();
        assertThat(mapper.readTree(current).path("required")).hasSize(2);
        assertThat(current).contains("skillVersionId");
        assertThat(RunToolPolicy.current(true, false)).contains("read_skill").doesNotContain("read_skill_resource");
        assertThat(RunToolPolicy.current(false, false)).doesNotContain("read_skill", "read_skill_resource");
        policy.put("toolPolicyVersion", 1).putArray("allowedTools").add("read_skill");
        assertThatThrownBy(() -> registry.modelDefinitions(policy)).isInstanceOf(IllegalStateException.class);
    }

    private AgentRun run(int policyVersion) {
        AgentRun run = mock(AgentRun.class);
        ObjectNode snapshot = mapper.createObjectNode();
        var selected = snapshot.putArray("creativeSkills");
        selected.add(skill(first, "first-skill", "First purpose", "PRIVATE_MAIN_FIRST", "😀甲😀乙"));
        selected.add(skill(second, "second-skill", "Second purpose", "PRIVATE_MAIN_SECOND", "SECOND_RESOURCE"));
        when(run.contextSnapshot()).thenReturn(snapshot);
        when(run.policySnapshot()).thenReturn(mapper.createObjectNode().put("toolPolicyVersion", policyVersion).put("systemPromptVersion", policyVersion == 2 ? 8 : 7));
        when(run.projectId()).thenReturn(UUID.randomUUID());when(run.id()).thenReturn(UUID.randomUUID());
        return run;
    }
    private ObjectNode skill(UUID id, String name, String description, String body, String content) {
        ObjectNode skill = mapper.createObjectNode().put("skillVersionId", id.toString()).put("name", name).put("description", description).put("skillMd", body);
        skill.putArray("resources").addObject().put("path", "references/shared.md").put("content", content).put("contentHash", "synthetic-hash");
        skill.putArray("resourceManifest").addObject().put("path", "references/shared.md").put("contentHash", "synthetic-hash");
        skill.putArray("assets").addObject().put("alias", "HIDDEN_REFERENCE_ALIAS");
        skill.putArray("inputs");skill.putArray("outputKinds").add("IMAGE");
        return skill;
    }
    private String resourceArgs(UUID id, int offset, int limit) {
        return "{\"skillVersionId\":\"" + id + "\",\"path\":\"references/shared.md\",\"offset\":" + offset + ",\"limit\":" + limit + "}";
    }
    private void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("TOOL_ARGUMENT_INVALID");
    }
}
