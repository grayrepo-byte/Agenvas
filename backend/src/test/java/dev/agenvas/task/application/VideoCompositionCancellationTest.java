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
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
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
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

class VideoCompositionCancellationTest {
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), id = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskRepository repository = mock(TaskRepository.class);
    private final UsageService usage = mock(UsageService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private DirectMediaTaskService service;

    @BeforeEach void setup() {
        service = new DirectMediaTaskService(repository, mock(MediaDraftService.class), mock(ArtifactService.class),
                mock(AssetService.class), mock(CanvasItemQueryService.class), mock(CanvasService.class),
                mock(MediaCapabilityService.class), events, usage, mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(ProjectService.class), mock(AgentRunService.class), mock(dev.agenvas.settings.application.MediaStyleService.class),
                mock(dev.agenvas.asset.storage.MediaRelayService.class), mock(dev.agenvas.settings.application.PromptService.class),
                mock(dev.agenvas.provider.application.MediaFunctionService.class));
        when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(call -> {
            Supplier<?> mutation = call.getArgument(2);
            var change = (ProjectEventService.Change<?>) mutation.get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
    }

    @ParameterizedTest
    @EnumSource(value = Task.Status.class, names = {"READY", "RUNNING"})
    void unsubmittedCompositionCancellationClosesAndReleasesReservation(Task.Status status) {
        Task current = task(status, true, false), canceled = task(Task.Status.CANCELED, true, true);
        when(repository.find(owner, project, id)).thenReturn(Optional.of(current), Optional.of(canceled));
        when(repository.cancelVideoComposition(current, NOW)).thenReturn(true);
        assertThat(service.cancelQueued(owner, project, id)).isEqualTo(canceled);
        verify(usage).releaseUnsubmittedMediaTask(owner, canceled);
        verify(repository, never()).cancelQueuedDirect(any(), any(), any());
    }

    @Test void submittingCompositionSignalsRendererWithoutReleasingAnActiveReservation() {
        Task current = task(Task.Status.SUBMITTING, true, false), requested = task(Task.Status.SUBMITTING, true, true);
        when(repository.find(owner, project, id)).thenReturn(Optional.of(current), Optional.of(requested));
        when(repository.cancelVideoComposition(current, NOW)).thenReturn(true);
        assertThat(service.cancelQueued(owner, project, id)).isEqualTo(requested);
        assertThat(service.cancelQueued(owner, project, id)).isEqualTo(requested);
        verify(repository).cancelVideoComposition(current, NOW);
        verifyNoInteractions(usage);
    }

    @ParameterizedTest
    @EnumSource(value = Task.Status.class, names = {"RUNNING", "SUBMITTING"})
    void ordinaryExternalWorkDoesNotGainRunningCancellation(Task.Status status) {
        Task external = task(status, false, false);
        when(repository.find(owner, project, id)).thenReturn(Optional.of(external));
        assertThatThrownBy(() -> service.cancelQueued(owner, project, id)).isInstanceOf(ApiProblemException.class);
        verify(repository).cancelQueuedDirect(project, id, NOW);
        verify(repository, never()).cancelVideoComposition(any(), any());
        verifyNoInteractions(usage);
    }

    @Test void concurrentCompositionChangeCannotReturnASuccessfulCancellation() {
        when(repository.find(owner, project, id)).thenReturn(Optional.of(task(Task.Status.SUBMITTING, true, false)));
        assertThatThrownBy(() -> service.cancelQueued(owner, project, id)).isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(usage);
    }

    private Task task(Task.Status status, boolean composition, boolean cancel) {
        var input = mapper.createObjectNode();
        if (composition) input.putObject("videoComposition");
        return new Task(id, project, null, "synthetic", Task.Kind.VIDEO_GENERATION, status, cancel, input,
                "hash", null, null, 1, NOW, "worker", NOW.plusSeconds(30), 1, 0, null, NOW, NOW,
                status == Task.Status.CANCELED ? NOW : null);
    }
}
