package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

/** Worker identity comes from project persistence; task input cannot supply an owner. */
class TaskWorkerOwnerTest {
    private final TaskRepository repository = mock(TaskRepository.class);
    private final UUID taskId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final TaskService service = new TaskService(mock(AgentRunService.class),
            mock(ProjectService.class), mock(ArtifactService.class), mock(MediaDraftService.class),
            mock(CanvasService.class), repository, new TaskProperties(Duration.ofMinutes(30), 1),
            mock(ProjectEventService.class), mock(UsageService.class), new ObjectMapper(),
            Clock.systemUTC(), new ShutdownGate());

    @Test
    void readsTrustedOwnerForTheClaimedScope() {
        Task claimed = task(projectId, runId);
        Task current = task(projectId, runId);
        when(repository.findById(taskId)).thenReturn(Optional.of(current));
        when(repository.ownerId(taskId)).thenReturn(Optional.of(ownerId));

        assertThat(service.ownerForWorker(claimed)).isEqualTo(ownerId);
    }

    @Test
    void directTaskScopeAllowsAnAbsentRun() {
        Task current = task(projectId, null);
        when(repository.findById(taskId)).thenReturn(Optional.of(current));
        when(repository.ownerId(taskId)).thenReturn(Optional.of(ownerId));

        assertThat(service.ownerForWorker(task(projectId, null))).isEqualTo(ownerId);
    }

    @ParameterizedTest
    @EnumSource(ChangedScope.class)
    void mismatchedScopeCannotReadAnOwner(ChangedScope changed) {
        Task current = switch (changed) {
            case PROJECT -> task(UUID.randomUUID(), runId);
            case RUN -> task(projectId, UUID.randomUUID());
            case ABSENT_RUN -> task(projectId, null);
        };
        when(repository.findById(taskId)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.ownerForWorker(task(projectId, runId)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("TASK_LEASE_LOST"));
        verify(repository, never()).ownerId(taskId);
    }

    @Test
    void missingTaskFailsBeforeOwnerLookup() {
        when(repository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ownerForWorker(task(projectId, runId)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        verify(repository, never()).ownerId(taskId);
    }

    private Task task(UUID project, UUID run) {
        Task task = mock(Task.class);
        when(task.id()).thenReturn(taskId);
        when(task.projectId()).thenReturn(project);
        when(task.runId()).thenReturn(run);
        return task;
    }

    private enum ChangedScope { PROJECT, RUN, ABSENT_RUN }
}
