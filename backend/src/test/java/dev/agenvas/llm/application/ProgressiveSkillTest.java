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
        AgentRun run = run();
        var snapshots = (ObjectNode) run.contextSnapshot();
        snapshots.put("projectName", "Project").put("agentName", "Agent").put("agentInstruction", "Create")
                .put("aspectRatio", "LANDSCAPE_16_9");
        when(run.instruction()).thenReturn("Create a poster");
        var runs = mock(AgentRunService.class);
        when(runs.get(any(), any(), any())).thenReturn(run);
        var capabilities = mock(MediaCapabilityService.class);
        when(capabilities.publishedCandidates()).thenReturn(List.of());
        var artifacts = mock(ArtifactService.class);
        var messages = new InitialModelContextService(runs, capabilities).assemble(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String text = messages.stream().map(message -> message.getText()).reduce("", (a,b) -> a + b);
        assertThat(text).contains("name=first-skill", "name=second-skill", "description=First purpose", first.toString(), second.toString())
                .doesNotContain("PRIVATE_MAIN_FIRST", "PRIVATE_MAIN_SECOND", "references/shared.md", "😀甲😀乙", "SECOND_RESOURCE", "HIDDEN_REFERENCE_ALIAS");
        verifyNoInteractions(artifacts);
    }

    @Test void mainInstructionsActivateOnlyThroughCommittedLedgerAndAttachmentsAreVersionScoped() {
        AgentRun run = run();
        when(ledger.skillReads(any(), any())).thenReturn(List.of());
        assertInvalid(() -> reader.skillResource(run, UUID.randomUUID(), resourceArgs(first, 0, 2)));
        JsonNode main = reader.skill(run, UUID.randomUUID(), "{\"skillVersionId\":\"" + first + "\"}").path("data");
        assertThat(main.path("content").asText()).isEqualTo("PRIVATE_MAIN_FIRST");
        assertThat(main.has("assetDelivery")).isFalse();
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

    @Test void currentManifestPlansUnicodePagesWithoutEagerlyLoadingResourceBodies() {
        AgentRun run = run();
        ((ObjectNode) run.policySnapshot()).put("systemPromptVersion", InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION);
        String content = "😀".repeat(4_100) + "PAGE_END_SENTINEL";
        ((ObjectNode) run.contextSnapshot().path("creativeSkills").path(0).path("resources").path(0))
                .put("content", content);
        when(ledger.skillReads(any(), any())).thenReturn(List.of());
        JsonNode main = reader.skill(run, UUID.randomUUID(), "{\"skillVersionId\":\"" + first + "\"}").path("data");
        assertThat(main.path("resourceManifest").path(0).path("total").asInt()).isEqualTo(4_117);
        assertThat(main.path("resourcePageLimit").asInt()).isEqualTo(4_000);
        assertThat(main.toString()).doesNotContain("PAGE_END_SENTINEL", "😀", "SECOND_RESOURCE");
        when(ledger.skillReads(any(), any())).thenReturn(List.of(main));
        JsonNode firstPage = reader.skillResource(run, UUID.randomUUID(), resourceArgs(first, 0, 4_000)).path("data");
        assertThat(firstPage.path("content").asText()).isEqualTo("😀".repeat(4_000));
        assertThat(firstPage.path("nextOffset").asInt()).isEqualTo(4_000);
        JsonNode lastPage = reader.skillResource(run, UUID.randomUUID(), resourceArgs(first, firstPage.path("nextOffset").asInt(), 4_000)).path("data");
        assertThat(firstPage.path("content").asText() + lastPage.path("content").asText()).isEqualTo(content);
        assertThat(lastPage.path("endOffset").asInt()).isEqualTo(4_117);
        assertThat(lastPage.path("nextOffset").isNull()).isTrue();
        assertInvalid(() -> reader.skillResource(run, UUID.randomUUID(), resourceArgs(first, 4_118, 4_000)));
    }

    @Test void imageReadsRequireCommittedActivationAndExactRegisteredAlias() {
        AgentRun run = run();
        var selected = (ObjectNode) run.contextSnapshot().path("creativeSkills").path(0);
        selected.put("skillId", UUID.randomUUID().toString()).put("assetDelivery", "LLM_CONTEXT");
        selected.putArray("assets").addObject().put("alias", "style").put("kind", "IMAGE")
                .put("contentHash", "fixed-image-hash").put("title", "Style").put("purpose", "Style reference");
        String args = "{\"skillVersionId\":\"" + first + "\",\"alias\":\"style\"}";
        when(ledger.skillReads(any(), any())).thenReturn(List.of());
        assertInvalid(() -> reader.skillAsset(run, UUID.randomUUID(), args));
        JsonNode main = reader.skill(run, UUID.randomUUID(), "{\"skillVersionId\":\"" + first + "\"}").path("data");
        assertInvalid(() -> reader.skillAsset(run, UUID.randomUUID(), args));
        when(ledger.skillReads(any(), any())).thenReturn(List.of(main));
        JsonNode read = reader.skillAsset(run, UUID.randomUUID(), args).path("data");
        assertThat(read.path("alias").asText()).isEqualTo("style");
        assertThat(read.path("contentHash").asText()).isEqualTo("fixed-image-hash");
        assertThat(read.path(AgentImageInputService.PREVIEW_REQUEST_KEY).asBoolean()).isTrue();
        assertThat(read.toString()).doesNotContain("artifactId", "versionId", "objectKey", "thumbnailKey", "base64");
        assertInvalid(() -> reader.skillAsset(run, UUID.randomUUID(), args.replace("style", "unknown")));
        assertInvalid(() -> reader.skillAsset(run, UUID.randomUUID(), args.replace(first.toString(), second.toString())));
    }

    private AgentRun run() {
        AgentRun run = mock(AgentRun.class);
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.putArray("selection");
        var selected = snapshot.putArray("creativeSkills");
        selected.add(skill(first, "first-skill", "First purpose", "PRIVATE_MAIN_FIRST", "😀甲😀乙"));
        selected.add(skill(second, "second-skill", "Second purpose", "PRIVATE_MAIN_SECOND", "SECOND_RESOURCE"));
        when(run.contextSnapshot()).thenReturn(snapshot);
        when(run.policySnapshot()).thenReturn(mapper.createObjectNode().put("toolPolicyVersion", RunToolPolicy.CURRENT_VERSION).put("systemPromptVersion", InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION));
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
