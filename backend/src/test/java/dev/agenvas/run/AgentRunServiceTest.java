package dev.agenvas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.*;
import dev.agenvas.run.domain.AgentConversation;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import dev.agenvas.skill.application.SkillRunService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Mock application boundaries isolate Run snapshots and transition ordering; PostgreSQL is tested separately. */
class AgentRunServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    @Test void transitionChecksVersionBeforeSameStateReplayOrTerminalStateRules() {
        UUID owner = UUID.randomUUID(), projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        var projects = mock(ProjectService.class);
        var rows = mock(AgentRunRepository.class);
        var events = mock(ProjectEventService.class);
        var cancellation = mock(RunTaskCancellation.class);
        var mapper = new ObjectMapper();
        var service = new AgentRunService(projects, mock(AgentInstanceService.class),
                mock(ArtifactService.class), mock(CanvasService.class), rows, events,
                cancellation, mock(RunTaskCreation.class), mock(ChatGateway.class), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), new ShutdownGate(), mock(AgentConversationService.class),
                mock(ConversationMemoryReader.class), mock(SkillRunService.class));
        var current = new AgentRun(runId, projectId, UUID.randomUUID(), UUID.randomUUID(), 1, owner,
                AgentRun.Status.CANCELED, "Synthetic canceled run", mapper.createObjectNode(),
                mapper.createObjectNode(), 1, 0, 7, NOW, NOW, NOW);
        when(rows.findForUpdate(owner, projectId, runId)).thenReturn(java.util.Optional.of(current));
        when(events.recordChange(eq(owner), eq(projectId), any())).thenAnswer(call -> {
            Supplier<ProjectEventService.Change<Object>> mutation = call.getArgument(2);
            var change = mutation.get();
            assertThat(change.event()).isNull();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });

        // Stale versions conflict even for a replay or an otherwise illegal terminal transition.
        for (var target : List.of(AgentRun.Status.CANCELED, AgentRun.Status.RUNNING)) {
            assertThatThrownBy(() -> service.transition(owner, projectId, runId, 6, target))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            error -> assertThat(error.code()).isEqualTo("RUN_VERSION_CONFLICT"));
        }
        assertThat(service.transition(owner, projectId, runId, 7, AgentRun.Status.CANCELED)).isSameAs(current);
        assertThatThrownBy(() -> service.transition(owner, projectId, runId, 7, AgentRun.Status.RUNNING))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("RUN_STATE_CONFLICT"));
        verify(rows, never()).updateStatus(any(), any(), any(), anyLong(), any(), any(), any());
        verify(projects, never()).releaseRunSlot(any(), any(), any());
        verify(events, never()).append(any(), any(), any());
        verifyNoInteractions(cancellation);
    }

    @Test void freezesMediaNodeResultsTextVersionsAndEmptyDraftSelections() {
        UUID owner = UUID.randomUUID(), projectId = UUID.randomUUID(), agentId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID(), versionId = UUID.randomUUID();
        UUID imageNodeId = UUID.randomUUID(), emptyNodeId = UUID.randomUUID();
        var projects = mock(ProjectService.class);
        var agents = mock(AgentInstanceService.class);
        var artifacts = mock(ArtifactService.class);
        var canvas = mock(CanvasService.class);
        var rows = mock(AgentRunRepository.class);
        var events = mock(ProjectEventService.class);
        var tasks = mock(RunTaskCreation.class);
        var gateway = mock(ChatGateway.class);
        var conversations = mock(AgentConversationService.class);
        var memory = mock(ConversationMemoryReader.class);
        var skills = mock(SkillRunService.class);
        var service = new AgentRunService(projects, agents, artifacts, canvas, rows, events,
                mock(RunTaskCancellation.class), tasks, gateway, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), new ShutdownGate(), conversations, memory, skills);
        var project = new Project(projectId, owner, "Synthetic project", Project.AspectRatio.SQUARE_1_1,
                Project.Status.ACTIVE, 0, 0, NOW, NOW, null);
        var agent = new AgentInstance(agentId, projectId, "creator", 1, "Creator", "Use the reference",
                UUID.randomUUID(), 0, NOW, NOW, List.of(new AgentInstance.Binding(UUID.randomUUID(), artifactId, versionId, NOW)));
        var conversation = new AgentConversation(UUID.randomUUID(), projectId, agentId, "", 0, 0, NOW, NOW);
        when(projects.requireActiveProject(owner, projectId)).thenReturn(project);
        when(agents.get(owner, projectId, agentId)).thenReturn(agent);
        when(gateway.configIdentity()).thenReturn(new ChatGateway.ConfigIdentity("mock", 1));
        when(rows.reserveIdempotency(eq(owner), anyString(), anyString(), anyString(), any(), eq(NOW))).thenReturn(true);
        when(rows.completeIdempotency(eq(owner), anyString(), anyString(), anyString(), any(), anyString(), eq(NOW))).thenReturn(true);
        when(conversations.resolveOrCreate(owner, projectId, agentId, null)).thenReturn(conversation);
        when(conversations.appendTurn(eq(owner), eq(conversation), anyString(), isNull(), eq(NOW)))
                .thenReturn(new AgentConversation(conversation.id(), projectId, agentId, "", 1, 1, NOW, NOW));
        when(memory.read(eq(projectId), anyList())).thenReturn(new ConversationMemoryReader.ConversationMemory(List.of(), false, 0));
        when(events.recordChange(eq(owner), eq(projectId), any())).thenAnswer(call -> {
            Supplier<ProjectEventService.Change<Object>> mutation = call.getArgument(2);
            return new ProjectEventService.RecordedChange<>(mutation.get().value(), null);
        });
        when(tasks.createInitialTurn(eq(projectId), any(), eq(NOW))).thenReturn(UUID.randomUUID());
        var media = mock(Artifact.class);
        when(media.kind()).thenReturn(Artifact.Kind.IMAGE);
        when(media.title()).thenReturn("Synthetic image");
        var view = new ArtifactService.ArtifactView(media, null);
        when(artifacts.get(owner, projectId, artifactId)).thenReturn(view);
        var imageNode = mock(CanvasItem.class);
        when(imageNode.id()).thenReturn(imageNodeId);
        when(imageNode.subjectId()).thenReturn(artifactId);
        when(imageNode.subjectType()).thenReturn(CanvasItem.SubjectType.ARTIFACT);
        var result = mock(ArtifactVersion.class);
        when(result.id()).thenReturn(versionId);
        var emptyNode = mock(CanvasItem.class);
        when(emptyNode.id()).thenReturn(emptyNodeId);
        when(emptyNode.subjectId()).thenReturn(UUID.randomUUID());
        when(emptyNode.subjectType()).thenReturn(CanvasItem.SubjectType.ARTIFACT);
        UUID textNodeId = UUID.randomUUID(), textVersionId = UUID.randomUUID();
        var textNode = mock(CanvasItem.class);
        when(textNode.id()).thenReturn(textNodeId);
        when(textNode.subjectId()).thenReturn(UUID.randomUUID());
        when(textNode.subjectType()).thenReturn(CanvasItem.SubjectType.ARTIFACT);
        var text = mock(Artifact.class);
        when(text.kind()).thenReturn(Artifact.Kind.TEXT);
        var textVersion = mock(ArtifactVersion.class);
        when(textVersion.id()).thenReturn(textVersionId);
        var textView = new ArtifactService.ArtifactView(text, textVersion);
        when(canvas.list(owner, projectId)).thenReturn(List.of(new CanvasService.CanvasEntry(imageNode, view, result, null),
                new CanvasService.CanvasEntry(emptyNode, view, null, null),
                new CanvasService.CanvasEntry(textNode, textView, null, null)));

        var run = service.create(owner, projectId, agentId, "Use image", "synthetic-request", null,
                List.of(imageNodeId, emptyNodeId, textNodeId)).run();
        assertThat(run.contextSnapshot().at("/bindings/0/selectedVersionId").asText()).isEqualTo(versionId.toString());
        assertThat(run.contextSnapshot().at("/bindings/0").has("expectedVersion")).isFalse();
        assertThat(run.contextSnapshot().at("/selection/0/versionId").asText()).isEqualTo(versionId.toString());
        assertThat(run.contextSnapshot().at("/selection/1/kind").asText()).isEqualTo("IMAGE");
        assertThat(run.contextSnapshot().at("/selection/1").has("versionId")).isFalse();
        assertThat(run.contextSnapshot().at("/selection/2/kind").asText()).isEqualTo("TEXT");
        assertThat(run.contextSnapshot().at("/selection/2/versionId").asText()).isEqualTo(textVersionId.toString());
        verify(rows).create(run);
        verify(tasks).createInitialTurn(projectId, run.id(), NOW);
        verify(artifacts, never()).setResourceDefaultVersion(any(), any(), any(), any(), anyLong());
    }
}
