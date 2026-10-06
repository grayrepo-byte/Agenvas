package dev.agenvas.llm.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AgentTurnCommitServiceTest {
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
                new LlmProtocolCodec(mapper), mock(AgentMediaOutcomeService.class), Clock.systemUTC());

        service.finishFailure(lease, "worker", "AGENT_TURN_FAILED");

        verify(tasks).fail(lease, "worker", "AGENT_TURN_FAILED");
        verify(runs).transition(owner, project, runId, 3L, AgentRun.Status.FAILED);
    }
}
