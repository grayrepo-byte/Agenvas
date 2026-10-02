package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Mock-only unit checks; PostgreSQL tests separately verify cancellation CAS and worker fencing. */
class ApprovedMediaCancellationTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskRepository repository = mock(TaskRepository.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final UsageService usage = mock(UsageService.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final TaskService service = new TaskService(runs, mock(ProjectService.class),
            mock(ArtifactService.class), mock(MediaDraftService.class), mock(CanvasService.class),
            repository, new TaskProperties(null, 0), events, usage, mapper,
            Clock.fixed(NOW, ZoneOffset.UTC), new ShutdownGate());

    @BeforeEach
    void transactionStub() {
        when(events.recordChange(eq(OWNER), eq(PROJECT), any())).thenAnswer(call -> {
            Supplier<?> mutation = call.getArgument(2);
            ProjectEventService.Change<?> change = (ProjectEventService.Change<?>) mutation.get();
            // Cancellation may append a usage event before its task event. A returned draft
            // would reuse the outer sequence and conflict in PostgreSQL.
            assertThat(change.event()).isNull();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
    }

    @Test
    void pendingApprovedTaskIsCanceledAndReleasesReservationWithoutReadingRun() {
        Task pending = task(Task.Status.READY, false, null);
        Task canceled = task(Task.Status.CANCELED, true, null);
        when(repository.find(OWNER, PROJECT, TASK)).thenReturn(Optional.of(pending));
        when(repository.cancelApprovedMedia(pending, NOW)).thenReturn(true);
        when(repository.findById(TASK)).thenReturn(Optional.of(canceled));
        assertThat(service.cancelApprovedMedia(OWNER, PROJECT, TASK)).isEqualTo(canceled);
        verify(usage).releaseUnsubmittedMediaTask(OWNER, canceled);
        verify(events).append(eq(OWNER), eq(PROJECT), any());
        verifyNoInteractions(runs);
    }

    @Test
    void unknownRequestOnlyRecordsCancellationAndKeepsReservation() {
        Task unknown = task(Task.Status.UNKNOWN, false, null);
        Task canceledIntent = task(Task.Status.UNKNOWN, true, null);
        when(repository.find(OWNER, PROJECT, TASK)).thenReturn(Optional.of(unknown));
        when(repository.cancelApprovedMedia(unknown, NOW)).thenReturn(true);
        when(repository.findById(TASK)).thenReturn(Optional.of(canceledIntent));
        assertThat(service.cancelApprovedMedia(OWNER, PROJECT, TASK).status()).isEqualTo(Task.Status.UNKNOWN);
        verifyNoInteractions(usage, runs);
    }

    @Test
    void runCancellationMarkerDoesNotLeaveAnUnsubmittedRunningWorkerActive() {
        Task running = task(Task.Status.RUNNING, true, null);
        Task canceled = task(Task.Status.CANCELED, true, null);
        when(repository.find(OWNER, PROJECT, TASK)).thenReturn(Optional.of(running));
        when(repository.cancelApprovedMedia(running, NOW)).thenReturn(true);
        when(repository.findById(TASK)).thenReturn(Optional.of(canceled));
        service.cancelApprovedMedia(OWNER, PROJECT, TASK);
        verify(repository).cancelApprovedMedia(running, NOW);
        verify(usage).releaseUnsubmittedMediaTask(OWNER, canceled);
    }

    @Test
    void failedApprovedMediaDoesNotApplyLegacyRunBlockRule() {
        Task running = task(Task.Status.RUNNING, false, null);
        Task failed = task(Task.Status.FAILED, false, null);
        when(repository.ownerId(TASK)).thenReturn(Optional.of(OWNER));
        when(repository.findById(TASK)).thenReturn(Optional.of(running), Optional.of(failed));
        when(repository.finish(TASK, "worker", 1, Task.Status.FAILED, null, "PROVIDER_REJECTED", NOW)).thenReturn(true);
        service.fail(running, "worker", "PROVIDER_REJECTED");
        verify(usage).releaseUnsubmittedMediaTask(OWNER, failed);
        verifyNoInteractions(runs);
    }

    @Test
    void unrelatedTaskCannotUseApprovedCancellationEndpoint() {
        Task direct = task(Task.Status.READY, false, null);
        Task unrelated = new Task(direct.id(), direct.projectId(), null, direct.stepKey(), direct.kind(), direct.status(),
                false, mapper.createObjectNode(), direct.inputHash(), null, null, null, 1, NOW,
                null, null, 0, 0, null, NOW, NOW, null);
        when(repository.find(OWNER, PROJECT, TASK)).thenReturn(Optional.of(unrelated));
        assertThatThrownBy(() -> service.cancelApprovedMedia(OWNER, PROJECT, TASK)).isInstanceOf(ApiProblemException.class);
        verify(repository, never()).cancelApprovedMedia(any(), any());
        verifyNoInteractions(usage);
    }

    private Task task(Task.Status status, boolean cancelRequested, String requestId) {
        return new Task(TASK, PROJECT, RUN, "approved-media", Task.Kind.AUDIO_GENERATION, status,
                cancelRequested, mapper.createObjectNode().put(Task.APPROVAL_INPUT_PROPERTY, UUID.randomUUID().toString()),
                "input-hash", null, null, requestId, 1, NOW, "worker", NOW.plusSeconds(30),
                1, 0, null, NOW, NOW, status == Task.Status.CANCELED || status == Task.Status.FAILED ? NOW : null);
    }
}
