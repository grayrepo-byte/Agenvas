package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventRecorded;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.event.domain.ProjectEvent;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Repository mocked; integration tests cover PostgreSQL lease locks and project-event commit order. */
class AgentStreamTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static final String WORKER = "agent-worker";
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskRepository repository = mock(TaskRepository.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final AtomicReference<Task> current = new AtomicReference<>();
    private final AgentRun run = mock(AgentRun.class);
    private final TaskService service = new TaskService(runs, mock(ProjectService.class), mock(ArtifactService.class),
            mock(MediaDraftService.class), mock(CanvasService.class), repository,
            new TaskProperties(Duration.ofSeconds(45), 3), events, mock(UsageService.class), mapper,
            Clock.fixed(NOW, ZoneOffset.UTC), new ShutdownGate());

    @BeforeEach
    void setup() {
        current.set(task(7, 1, Task.Status.RUNNING, false, null));
        when(repository.ownerId(taskId)).thenReturn(Optional.of(owner));
        when(repository.findById(taskId)).thenAnswer(ignored -> Optional.of(current.get()));
        when(repository.lockActiveAgentTurnLease(eq(project), eq(runId), eq(taskId), eq(WORKER), anyLong(), eq(NOW)))
                .thenAnswer(call -> (long) call.getArgument(4) == current.get().leaseEpoch() && !current.get().cancelRequested());
        when(repository.updateAgentStream(eq(taskId), eq(WORKER), anyLong(), anyLong(), any(), eq(NOW))).thenAnswer(call -> {
            current.set(task(current.get().leaseEpoch(), current.get().version() + 1, Task.Status.RUNNING, false, call.getArgument(4)));
            return true;
        });
        when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(call -> {
            ProjectEventService.Change<?> change = ((Supplier<ProjectEventService.Change<?>>) call.getArgument(2)).get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
        when(runs.get(owner, project, runId)).thenReturn(run);
        when(run.status()).thenReturn(AgentRun.Status.RUNNING);
        when(run.nextStepIndex()).thenReturn(0);
        when(run.version()).thenReturn(3L);
    }

    @Test
    void publicDeltasAreDurableBeforeTheirEventsAndFinalCheckpointCorrectsTheText() {
        Task lease = current.get();
        service.startAgentStream(lease, WORKER);
        assertThat(service.appendAgentStream(lease, WORKER, 0, "Hello ")).isEqualTo(1);
        service.appendAgentStream(lease, WORKER, 1, "world");
        service.completeAgentStream(lease, WORKER, "Hello world.");
        JsonNode progress = current.get().output().path("assistantStream");
        assertThat(progress.path("text").asText()).isEqualTo("Hello world.");
        assertThat(progress.path("chunkIndex").asLong()).isEqualTo(2);
        assertThat(progress.path("status").asText()).isEqualTo("COMPLETED");
        ArgumentCaptor<ProjectEventService.EventDraft> drafts = ArgumentCaptor.forClass(ProjectEventService.EventDraft.class);
        verify(events, times(4)).append(eq(owner), eq(project), drafts.capture());
        assertThat(drafts.getAllValues()).extracting(ProjectEventService.EventDraft::type)
                .containsExactly("agent.turn.stream.started", "agent.turn.stream.delta", "agent.turn.stream.delta", "agent.turn.stream.completed");
        assertThat(drafts.getAllValues().get(1).payload().path("textDelta").asText()).isEqualTo("Hello ");
        assertThat(drafts.getAllValues().getLast().payload().path("chunkIndex").asLong()).isEqualTo(2);
        assertThat(drafts.getAllValues().getLast().payload().has("textDelta")).isFalse();
    }

    @Test
    void duplicateOrSkippedChunkAndOldEpochCannotAppendOrComplete() {
        Task lease = current.get();
        service.startAgentStream(lease, WORKER);
        service.appendAgentStream(lease, WORKER, 0, "kept");
        assertLeaseLost(() -> service.appendAgentStream(lease, WORKER, 0, "duplicated"));
        assertLeaseLost(() -> service.appendAgentStream(lease, WORKER, 3, "skipped"));
        current.set(task(8, 10, Task.Status.RUNNING, false, current.get().output()));
        assertLeaseLost(() -> service.completeAgentStream(lease, WORKER, "late"));
        service.startAgentStream(current.get(), WORKER);
        assertThat(current.get().output().path("assistantStream").path("text").asText()).isEmpty();
        assertThat(current.get().output().path("assistantStream").path("streamEpoch").asLong()).isEqualTo(8);
    }

    @Test
    void stoppedRunAndCompletedStreamRejectNewDeltas() {
        Task lease = current.get();
        service.startAgentStream(lease, WORKER);
        when(run.status()).thenReturn(AgentRun.Status.CANCELED);
        assertLeaseLost(() -> service.appendAgentStream(lease, WORKER, 0, "late"));
        when(run.status()).thenReturn(AgentRun.Status.RUNNING);
        service.completeAgentStream(lease, WORKER, "done");
        assertLeaseLost(() -> service.appendAgentStream(lease, WORKER, 0, "late"));
    }

    @Test
    void failedTurnPreservesPartialTextAndPublishesInterruptionAtSameCursor() {
        Task lease = current.get();
        service.startAgentStream(lease, WORKER);
        service.appendAgentStream(lease, WORKER, 0, "partial answer");
        when(repository.finish(eq(taskId), eq(WORKER), eq(7L), eq(Task.Status.FAILED), any(), eq("AGENT_TURN_FAILED"), eq(NOW)))
                .thenAnswer(call -> {
                    current.set(task(7, 4, Task.Status.FAILED, false, call.getArgument(4)));
                    return true;
                });
        service.fail(lease, WORKER, "AGENT_TURN_FAILED");
        assertThat(current.get().output().path("assistantStream").path("text").asText()).isEqualTo("partial answer");
        assertThat(current.get().output().path("assistantStream").path("status").asText()).isEqualTo("INTERRUPTED");
        ArgumentCaptor<ProjectEventService.EventDraft> drafts = ArgumentCaptor.forClass(ProjectEventService.EventDraft.class);
        verify(events, times(4)).append(eq(owner), eq(project), drafts.capture());
        assertThat(drafts.getAllValues().get(2).type()).isEqualTo("agent.turn.stream.interrupted");
        assertThat(drafts.getAllValues().get(2).payload().path("chunkIndex").asLong()).isEqualTo(1);
    }

    @Test
    void committedAssistantTextKeepsTheDurableCompletedProjection() {
        Task lease = current.get();
        service.startAgentStream(lease, WORKER);
        service.appendAgentStream(lease, WORKER, 0, "answer");
        service.completeAgentStream(lease, WORKER, "answer");
        when(repository.finish(eq(taskId), eq(WORKER), eq(7L), eq(Task.Status.SUCCEEDED), any(), isNull(), eq(NOW)))
                .thenAnswer(call -> { current.set(task(7, 5, Task.Status.SUCCEEDED, false, call.getArgument(4))); return true; });
        service.succeed(lease, WORKER, mapper.createObjectNode().put("assistantText", "answer").put("decision", "FINISH"));
        assertThat(current.get().output().path("assistantText").asText()).isEqualTo("answer");
        assertThat(current.get().output().path("assistantStream").path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void cancellationSignalOnlyPublishesSafeInterruptedProjection() {
        JsonNode output = mapper.createObjectNode().set("assistantStream", mapper.createObjectNode()
                .put("streamEpoch", 7).put("chunkIndex", 1).put("text", "partial").put("status", "INTERRUPTED"));
        Task canceled = task(7, 4, Task.Status.RUNNING, true, output);
        when(repository.listByRun(owner, project, runId)).thenReturn(List.of(canceled));
        service.onRunCanceled(new ProjectEventRecorded(owner, new ProjectEvent(project, 1, UUID.randomUUID(),
                "agent.run.changed", 1, runId, 4, mapper.createObjectNode().put("status", "CANCELED"), NOW)));
        ArgumentCaptor<ProjectEventService.EventDraft> draft = ArgumentCaptor.forClass(ProjectEventService.EventDraft.class);
        verify(events).append(eq(owner), eq(project), draft.capture());
        assertThat(draft.getValue().type()).isEqualTo("agent.turn.stream.interrupted");
        assertThat(draft.getValue().payload().has("textDelta")).isFalse();
    }

    private Task task(long epoch, long version, Task.Status status, boolean canceled, JsonNode output) {
        return new Task(taskId, project, runId, "agent-turn-0", Task.Kind.AGENT_TURN, status, canceled,
                mapper.createObjectNode().put("schemaVersion", 1).put("stepIndex", 0), "hash", output, null, 1, NOW, WORKER, NOW.plusSeconds(45), epoch, version, null, NOW, NOW, null);
    }

    private static void assertLeaseLost(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiProblemException.class,
                failure -> assertThat(failure.code()).isEqualTo("TASK_LEASE_LOST"));
    }
}
