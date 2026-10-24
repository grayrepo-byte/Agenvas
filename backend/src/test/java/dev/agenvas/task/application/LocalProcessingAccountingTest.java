package dev.agenvas.task.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.application.AgentRunService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class LocalProcessingAccountingTest {
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), id = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskRepository repository = mock(TaskRepository.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final UsageService usage = mock(UsageService.class);
    private final TaskService service = new TaskService(mock(AgentRunService.class), mock(ProjectService.class),
            mock(ArtifactService.class), mock(MediaDraftService.class), mock(CanvasService.class), repository,
            new TaskProperties(null, 0), events, usage, mapper, Clock.fixed(NOW, ZoneOffset.UTC), new ShutdownGate());

    @BeforeEach void setup() {
        when(repository.ownerId(id)).thenReturn(Optional.of(owner));
        when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(call -> {
            Supplier<?> mutation = call.getArgument(2);
            var change = (ProjectEventService.Change<?>) mutation.get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUDIO_TRIM_INVALID", "AUDIO_TRIM_OUTPUT_TOO_LARGE"})
    void preflightFailureCanBlockAndReleaseBeforeAnySubmission(String code) {
        Task lease = task("audioTrim", Task.Status.RUNNING, null);
        Task blocked = task("audioTrim", Task.Status.BLOCKED, code);
        when(repository.findById(id)).thenReturn(Optional.of(lease), Optional.of(blocked));
        when(repository.blockStaleInput(id, "worker", 1, code, NOW)).thenReturn(true);
        service.blockPreSubmission(lease, "worker", code);
        verify(usage).releaseUnsubmittedMediaTask(owner, blocked);
    }

    @ParameterizedTest
    @CsvSource({"audioTrim,LOCAL_AUDIO_TRIM_FAILED", "audioTrim,LOCAL_AUDIO_TRIM_TIMEOUT",
            "videoComposition,LOCAL_VIDEO_COMPOSITION_FAILED", "videoComposition,LOCAL_VIDEO_COMPOSITION_TIMEOUT"})
    void confirmedLocalFailureOrTimeoutClosesItsReservation(String type, String code) {
        Task lease = task(type, Task.Status.SUBMITTING, null);
        Task failed = task(type, Task.Status.FAILED, code);
        when(repository.findById(id)).thenReturn(Optional.of(failed));
        when(repository.rejectSubmission(lease, "worker", code, NOW)).thenReturn(true);
        service.rejectSubmission(lease, "worker", code);
        verify(usage).releaseUnsubmittedMediaTask(owner, failed);
    }

    @Test void externalRejectionDoesNotGainLocalReleaseSemantics() {
        Task lease = task("external", Task.Status.SUBMITTING, null);
        Task failed = task("external", Task.Status.FAILED, "PROVIDER_REJECTED");
        when(repository.findById(id)).thenReturn(Optional.of(failed));
        when(repository.rejectSubmission(lease, "worker", "PROVIDER_REJECTED", NOW)).thenReturn(true);
        service.rejectSubmission(lease, "worker", "PROVIDER_REJECTED");
        verifyNoInteractions(usage);
    }

    private Task task(String type, Task.Status status, String code) {
        var input = mapper.createObjectNode();
        if (!"external".equals(type)) input.putObject(type);
        Task.Kind kind = "videoComposition".equals(type) ? Task.Kind.VIDEO_GENERATION : Task.Kind.AUDIO_GENERATION;
        return new Task(id, project, null, "synthetic", kind, status, false, input, "hash", null, null,
                1, NOW, "worker", NOW.plusSeconds(30), 1, 0, code, NOW, NOW, null);
    }
}
