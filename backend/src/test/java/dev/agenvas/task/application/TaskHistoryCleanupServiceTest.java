package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class TaskHistoryCleanupServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final TaskHistoryCleanupService service = new TaskHistoryCleanupService(tasks, events, new ObjectMapper());

    @Test void emitsVersionedCancellationUsingTheActualProjectOwner() {
        UUID id = UUID.randomUUID(), project = UUID.randomUUID(), owner = UUID.randomUUID();
        Task stopped = mock(Task.class);
        when(stopped.id()).thenReturn(id); when(stopped.projectId()).thenReturn(project);
        when(stopped.status()).thenReturn(Task.Status.CANCELED); when(stopped.version()).thenReturn(7L);
        when(tasks.stopForHistoryCleanup(List.of(id), NOW)).thenReturn(List.of(stopped));
        when(tasks.ownerId(id)).thenReturn(Optional.of(owner));
        service.stopFor(List.of(id), NOW);
        var event = ArgumentCaptor.forClass(ProjectEventService.EventDraft.class);
        verify(events).append(eq(owner), eq(project), event.capture());
        assertThat(event.getValue().aggregateId()).isEqualTo(id);
        assertThat(event.getValue().aggregateVersion()).isEqualTo(7L);
        assertThat(event.getValue().payload().path("cancelRequested").asBoolean()).isTrue();
        assertThat(event.getValue().payload().path("possibleExternalCost").asBoolean()).isTrue();
        assertThat(event.getValue().payload().path("status").asText()).isEqualTo("CANCELED");
    }

    @Test void alreadyFinishedUnitsDoNotEmitCancellation() {
        when(tasks.stopForHistoryCleanup(List.of(), NOW)).thenReturn(List.of());
        service.stopFor(List.of(), NOW); verifyNoInteractions(events);
    }

    @Test void eventFailurePropagatesSoTheCoordinatorCanRollbackTheBatch() {
        UUID id = UUID.randomUUID(); Task stopped = mock(Task.class);
        when(stopped.id()).thenReturn(id); when(stopped.projectId()).thenReturn(id);
        when(stopped.status()).thenReturn(Task.Status.CANCELED);
        when(tasks.stopForHistoryCleanup(List.of(id), NOW)).thenReturn(List.of(stopped));
        when(tasks.ownerId(id)).thenReturn(Optional.of(id));
        when(events.append(any(), any(), any())).thenThrow(new IllegalStateException("event write failed"));
        assertThatThrownBy(() -> service.stopFor(List.of(id), NOW)).isInstanceOf(IllegalStateException.class);
    }
}
