package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.llm.application.ReadToolService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Tool arguments and Run scope remain enforced even when a task belongs to the project. */
class ReadToolServiceTest {

    private final TaskService tasks = mock(TaskService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReadToolService reader = new ReadToolService(
            mock(ProjectService.class), artifacts, tasks, mapper, mock(dev.agenvas.llm.application.ToolExecutionRepository.class));
    private final TrustedToolContext context = new TrustedToolContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @ParameterizedTest
    @EnumSource(value = Artifact.Kind.class, names = {"IMAGE", "VIDEO", "AUDIO"})
    void readsVisibleMediaWithoutAResourceDefault(Artifact.Kind kind) {
        AgentRun run = mock(AgentRun.class);
        JsonNode snapshot = mapper.createObjectNode();
        when(run.contextSnapshot()).thenReturn(snapshot);
        when(run.policySnapshot()).thenReturn(mapper.createObjectNode().put("systemPromptVersion", 5));
        Artifact artifact = mock(Artifact.class);
        ArtifactVersion version = mock(ArtifactVersion.class);
        UUID artifactId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        JsonNode content = mapper.createObjectNode().put("assetId", UUID.randomUUID().toString());
        when(version.id()).thenReturn(versionId);
        when(version.artifactId()).thenReturn(artifactId);
        when(version.runId()).thenReturn(context.runId());
        when(version.content()).thenReturn(content);
        when(artifact.kind()).thenReturn(kind);
        when(artifacts.requireAgentVisibleVersion(context.ownerId(), context.projectId(),
                context.runId(), versionId, snapshot)).thenReturn(version);
        when(artifacts.get(context.ownerId(), context.projectId(), artifactId))
                .thenReturn(new ArtifactService.ArtifactView(artifact, null));

        JsonNode item = reader.artifacts(context, run, UUID.randomUUID(),
                mapper.createObjectNode().set("versionIds",
                        mapper.createArrayNode().add(versionId.toString())).toString()).path("data").get(0);

        assertThat(item.path("versionId").asText()).isEqualTo(versionId.toString());
        assertThat(item.path("kind").asText()).isEqualTo(kind.name());
        assertThat(item.path("imagePreviewRequested").asBoolean()).isEqualTo(kind == Artifact.Kind.IMAGE);
        assertThat(item.path("current").asBoolean()).isFalse();
        assertThat(item.path("content")).isEqualTo(content);
        assertThat(item.path("contentTruncated").asBoolean()).isFalse();
        assertThat(item.has("title")).isFalse();
        assertThat(item.has("expectedVersion")).isFalse();
    }

    @Test
    void missingSkillResourceDoesNotClaimTheReadToolsAreUnavailable() {
        AgentRun run = mock(AgentRun.class);
        when(run.contextSnapshot()).thenReturn(mapper.createObjectNode());
        when(run.policySnapshot()).thenReturn(mapper.createObjectNode().put("toolPolicyVersion", 1));
        assertThatThrownBy(() -> reader.skillResource(run, UUID.randomUUID(), "{\"path\":\"guide.md\"}"))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("资源")
                .hasMessageNotContaining("允许列表");
    }

    @Test
    void rejectsAnotherRunTaskWithoutReturningItsStatus() {
        UUID taskId = UUID.randomUUID();
        Task foreignRunTask = mock(Task.class);
        when(tasks.get(context.ownerId(), context.projectId(), taskId))
                .thenReturn(foreignRunTask);
        when(foreignRunTask.runId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> reader.taskStatus(context, UUID.randomUUID(),
                "{\"taskIds\":[\"" + taskId + "\"]}"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("TASK_NOT_FOUND");
    }

    @Test
    void rejectsOversizedAndDuplicateListsBeforeReadingAnyTask() {
        String oneId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> reader.taskStatus(context, UUID.randomUUID(),
                "{\"taskIds\":[\"" + oneId + "\",\"" + oneId + "\"]}"))
                .isInstanceOf(ApiProblemException.class);
        String thirteen = (",\"" + oneId + "\"").repeat(12);
        assertThatThrownBy(() -> reader.taskStatus(context, UUID.randomUUID(),
                "{\"taskIds\":[\"" + oneId + "\"" + thirteen + "]}"))
                .isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(tasks);
    }
}
