package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.AgentTurnLeaseGuard;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class AgentTurnCommitServiceTest {
    @Test void compactionPreservesTheInterruptedRepairBudgetWithoutReplayingTheRepairSource() {
        var mapper = new JsonMapper();
        var codec = new LlmProtocolCodec(mapper);
        var owner = UUID.randomUUID();
        var project = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var lease = mock(Task.class);
        when(lease.id()).thenReturn(UUID.randomUUID());
        when(lease.projectId()).thenReturn(project);
        when(lease.runId()).thenReturn(runId);
        when(lease.kind()).thenReturn(Task.Kind.AGENT_TURN);
        when(lease.input()).thenReturn(mapper.createObjectNode().put("schemaVersion", 1).put("stepIndex", 4)
                .put("repairAttempt", 2).put("maxRepairAttempts", 4).put("repairFromStep", 3));
        var repository = mock(TaskRepository.class);
        when(repository.ownerId(lease.id())).thenReturn(Optional.of(owner));
        var tasks = mock(TaskService.class);
        var runs = mock(AgentRunService.class);
        var run = mock(AgentRun.class);
        when(run.status()).thenReturn(AgentRun.Status.RUNNING);
        when(run.nextStepIndex()).thenReturn(4);
        when(run.version()).thenReturn(3L);
        when(runs.get(owner, project, runId)).thenReturn(run);
        var request = codec.request(List.of(new UserMessage("Summarize public state")), List.of());
        request.putObject(AgentContextService.COMPACTION);
        var response = codec.response(new ChatResponse(List.of(
                new Generation(new AssistantMessage("Public summary")))));
        var turns = mock(LlmTurnRepository.class);
        when(turns.find(project, runId, 4)).thenReturn(Optional.of(new LlmTurn(project, runId, 4,
                LlmTurn.Status.RESPONDED, 1, request, response, Instant.now(), Instant.now())));
        var events = mock(ProjectEventService.class);
        when(events.recordChange(any(), any(), any())).thenAnswer(invocation -> {
            Supplier<ProjectEventService.Change<Object>> mutation = invocation.getArgument(2);
            return new ProjectEventService.RecordedChange<>(mutation.get().value(), null);
        });
        var service = new AgentTurnCommitService(repository, tasks, runs, mock(AgentTurnLeaseGuard.class), events,
                mapper, turns, mock(ToolExecutionRepository.class), codec, mock(AgentMediaOutcomeService.class),
                Clock.systemUTC(), source(DirectorSettings.defaults()));

        assertThat(service.complete(lease, "worker")).isEqualTo(AgentTurnCommitService.Decision.CONTINUE);

        var next = ArgumentCaptor.forClass(JsonNode.class);
        verify(tasks).create(eq(owner), eq(project), eq(runId), eq("agent-turn-5"), eq(Task.Kind.AGENT_TURN), next.capture(), eq(1));
        assertThat(next.getValue().path("repairAttempt").asInt()).isEqualTo(2);
        assertThat(next.getValue().path("maxRepairAttempts").asInt()).isEqualTo(4);
        assertThat(next.getValue().has("repairFromStep")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void lateRunRepairsContinueWithoutGlobalCountLimitsButRetainTheirLocalAttemptLimit(int attempt) {
        var mapper = new JsonMapper();
        var owner = UUID.randomUUID();
        var project = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var lease = mock(Task.class);
        when(lease.id()).thenReturn(UUID.randomUUID());
        when(lease.projectId()).thenReturn(project);
        when(lease.runId()).thenReturn(runId);
        when(lease.kind()).thenReturn(Task.Kind.AGENT_TURN);
        when(lease.input()).thenReturn(mapper.createObjectNode().put("schemaVersion", 1)
                .put("stepIndex", 90).put("repairAttempt", attempt));
        var repository = mock(TaskRepository.class);
        when(repository.ownerId(lease.id())).thenReturn(Optional.of(owner));
        var tasks = mock(TaskService.class);
        when(tasks.hasSettledAgentFailure(owner, project, runId, 90)).thenReturn(true);
        var runs = mock(AgentRunService.class);
        var run = mock(AgentRun.class);
        when(run.status()).thenReturn(AgentRun.Status.RUNNING);
        when(run.nextStepIndex()).thenReturn(90);
        when(run.version()).thenReturn(3L);
        when(runs.get(owner, project, runId)).thenReturn(run);
        var turns = mock(LlmTurnRepository.class);
        var turn = mock(LlmTurn.class);
        when(turn.status()).thenReturn(LlmTurn.Status.RESPONDED);
        when(turns.find(project, runId, 90)).thenReturn(Optional.of(turn));
        var events = mock(ProjectEventService.class);
        when(events.recordChange(any(), any(), any())).thenAnswer(invocation -> {
            Supplier<ProjectEventService.Change<Object>> mutation = invocation.getArgument(2);
            var change = mutation.get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
        var service = new AgentTurnCommitService(repository, tasks, runs, mock(AgentTurnLeaseGuard.class),
                events, mapper, turns, mock(ToolExecutionRepository.class), new LlmProtocolCodec(mapper),
                mock(AgentMediaOutcomeService.class), Clock.systemUTC(), source(DirectorSettings.defaults()));

        assertThat(service.scheduleRepair(lease, "worker", "TOOL_ARGUMENT_INVALID", "Synthetic invalid argument"))
                .isEqualTo(attempt < DirectorSettings.defaults().maxRepairAttempts());
        if (attempt < DirectorSettings.defaults().maxRepairAttempts()) {
            verify(tasks).create(eq(owner), eq(project), eq(runId), eq("agent-turn-91"),
                    eq(Task.Kind.AGENT_TURN), any(), eq(1));
            verify(runs).advanceStep(owner, project, runId, 3L, 90);
            verify(tasks, never()).fail(any(), anyString(), anyString());
        } else {
            verify(tasks).fail(lease, "worker", "MODEL_OUTPUT_INVALID");
            verify(runs).transition(owner, project, runId, 3L, AgentRun.Status.FAILED);
            verify(tasks, never()).create(any(), any(), any(), anyString(), any(), any(), anyInt());
        }
    }

    @Test void fatalModelFailureEndsTheRunInsteadOfLeavingTheProjectOccupied() {
        var mapper = new JsonMapper();
        var owner = UUID.randomUUID();
        var project = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var lease = mock(Task.class);
        when(lease.id()).thenReturn(UUID.randomUUID());
        when(lease.projectId()).thenReturn(project);
        when(lease.runId()).thenReturn(runId);
        when(lease.kind()).thenReturn(Task.Kind.AGENT_TURN);
        when(lease.input()).thenReturn(mapper.createObjectNode().put("schemaVersion", 1).put("stepIndex", 0));
        var repository = mock(TaskRepository.class);
        when(repository.ownerId(lease.id())).thenReturn(Optional.of(owner));
        var tasks = mock(TaskService.class);
        when(tasks.hasSettledAgentFailure(owner, project, runId, 0)).thenReturn(true);
        var runs = mock(AgentRunService.class);
        var run = mock(AgentRun.class);
        when(run.status()).thenReturn(AgentRun.Status.RUNNING);
        when(run.version()).thenReturn(3L);
        when(runs.get(owner, project, runId)).thenReturn(run);
        var events = mock(ProjectEventService.class);
        doAnswer(invocation -> {
            Supplier<?> mutation = invocation.getArgument(2);
            mutation.get();
            return null;
        }).when(events).recordChange(any(), any(), any());
        var service = new AgentTurnCommitService(repository, tasks, runs, mock(AgentTurnLeaseGuard.class),
                events, mapper, mock(LlmTurnRepository.class), mock(ToolExecutionRepository.class),
                new LlmProtocolCodec(mapper), mock(AgentMediaOutcomeService.class), Clock.systemUTC(), source(DirectorSettings.defaults()));

        service.finishFailure(lease, "worker", "AGENT_TURN_FAILED");

        verify(tasks).fail(lease, "worker", "AGENT_TURN_FAILED");
        verify(runs).transition(owner, project, runId, 3L, AgentRun.Status.FAILED);
    }
    private static DirectorSettingsService source(DirectorSettings value) {
        var source = org.mockito.Mockito.mock(DirectorSettingsService.class);
        org.mockito.Mockito.when(source.get()).thenReturn(value);
        return source;
    }

}
