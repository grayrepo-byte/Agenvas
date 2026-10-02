package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.AgentTurnLeaseGuard;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Mocked lease guard; PostgreSQL tests exercise the row lock and transaction rollback boundary. */
class LlmTurnStreamFenceTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final LlmTurnRepository turns = mock(LlmTurnRepository.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final UsageService usage = mock(UsageService.class);
    private final AgentTurnLeaseGuard guard = mock(AgentTurnLeaseGuard.class);
    private final TaskService tasks = mock(TaskService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmProtocolCodec codec = new LlmProtocolCodec(mapper);
    private final LlmTurnCheckpointService service = new LlmTurnCheckpointService(runs, turns, events, mapper,
            Clock.fixed(NOW, ZoneOffset.UTC), usage, guard, tasks, codec);
    private final Task lease = mock(Task.class);
    private final AgentRun run = mock(AgentRun.class);
    private final JsonNode request = mapper.createObjectNode().put("schemaVersion", 1);
    private final JsonNode response = codec.response(new ChatResponse(List.of(new Generation(new AssistantMessage("Visible answer")))));

    @BeforeEach
    void setup() {
        when(lease.projectId()).thenReturn(project);
        when(lease.runId()).thenReturn(runId);
        when(lease.input()).thenReturn(mapper.createObjectNode().put("stepIndex", 0));
        when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(call -> {
            ProjectEventService.Change<?> change = ((Supplier<ProjectEventService.Change<?>>) call.getArgument(2)).get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
        when(runs.find(owner, project, runId)).thenReturn(Optional.of(run));
        when(run.status()).thenReturn(AgentRun.Status.RUNNING);
        when(run.id()).thenReturn(runId);
        when(run.policySnapshot()).thenReturn(mapper.createObjectNode().put("modelConfigVersion", 3).put("modelConfigSource", "mock"));
        when(run.nextStepIndex()).thenReturn(0);
        when(turns.find(project, runId, 0)).thenReturn(Optional.of(turn(LlmTurn.Status.REQUESTED, null)));
    }

    @Test
    void lostEpochCannotReserveUsageOrSaveACompleteLateResponse() {
        doThrow(new IllegalStateException("Lease lost")).when(guard).requireActive(lease, "worker");
        assertThatThrownBy(() -> service.reserveLeased(owner, project, runId, 0, 3, "mock", request, lease, "worker"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> save()).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(turns, usage, tasks);
    }

    @Test
    void stoppedRunAndAdvancedModelStepCannotSaveOrCompleteStream() {
        when(run.status()).thenReturn(AgentRun.Status.CANCELED);
        assertConflict(this::save);
        when(run.status()).thenReturn(AgentRun.Status.RUNNING);
        when(run.nextStepIndex()).thenReturn(1);
        assertConflict(this::save);
        verifyNoInteractions(turns, usage, tasks);
    }

    @Test
    void validLeaseCompletesPublicProjectionBeforeSavingProtocolAndSettlingUsage() {
        LlmTurn saved = turn(LlmTurn.Status.RESPONDED, response);
        when(turns.saveResponse(project, runId, 0, response, NOW)).thenReturn(true);
        when(turns.find(project, runId, 0)).thenReturn(Optional.of(turn(LlmTurn.Status.REQUESTED, null)), Optional.of(saved));
        assertThat(save()).isEqualTo(saved);
        var order = inOrder(guard, tasks, turns, usage);
        order.verify(guard).requireActive(lease, "worker");
        order.verify(turns).find(project, runId, 0);
        order.verify(tasks).completeAgentStream(lease, "worker", "Visible answer");
        order.verify(turns).saveResponse(project, runId, 0, response, NOW);
        order.verify(turns).find(project, runId, 0);
        order.verify(usage).settleModelTurn(owner, saved);
    }

    @Test
    void interruptedProjectionRefusesLateCheckpointBeforeResponseOrUsageWrites() {
        doThrow(new IllegalStateException("Stream interrupted")).when(tasks).completeAgentStream(lease, "worker", "Visible answer");
        assertThatThrownBy(this::save).isInstanceOf(IllegalStateException.class);
        verify(turns, never()).saveResponse(any(), any(), anyInt(), any(), any());
        verifyNoInteractions(usage);
    }

    private LlmTurn save() { return service.saveResponseLeased(owner, project, runId, 0, 3, response, lease, "worker"); }
    private LlmTurn turn(LlmTurn.Status status, JsonNode response) { return new LlmTurn(project, runId, 0, status, 3, request, response, NOW, null); }
    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiProblemException.class,
                failure -> assertThat(failure.code()).isEqualTo("LLM_TURN_CONFLICT"));
    }
}
