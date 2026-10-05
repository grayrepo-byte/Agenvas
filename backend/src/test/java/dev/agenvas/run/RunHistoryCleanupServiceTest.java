package dev.agenvas.run;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.application.RunHistoryCleanupService;
import dev.agenvas.run.domain.AgentRun;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class RunHistoryCleanupServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final RunHistoryCleanupService service = new RunHistoryCleanupService(runs, projects, events, new ObjectMapper());

    @Test void releasesOnlyTheStoppedRunsSlotAndEmitsItsNewVersion() {
        UUID id = UUID.randomUUID(), project = UUID.randomUUID(), owner = UUID.randomUUID();
        AgentRun stopped = mock(AgentRun.class);
        when(stopped.id()).thenReturn(id); when(stopped.projectId()).thenReturn(project);
        when(stopped.userId()).thenReturn(owner); when(stopped.status()).thenReturn(AgentRun.Status.CANCELED);
        when(stopped.version()).thenReturn(4L); when(stopped.nextStepIndex()).thenReturn(2);
        when(runs.stopForHistoryCleanup(List.of(id), NOW)).thenReturn(List.of(stopped));
        service.stopFor(List.of(id), NOW);
        verify(projects).releaseRunSlotIfHeld(owner, project, id);
        var event = ArgumentCaptor.forClass(ProjectEventService.EventDraft.class);
        verify(events).append(eq(owner), eq(project), event.capture());
        assertThat(event.getValue().aggregateVersion()).isEqualTo(4L);
        assertThat(event.getValue().payload().path("status").asText()).isEqualTo("CANCELED");
        assertThat(event.getValue().payload().path("nextStepIndex").asInt()).isEqualTo(2);
    }

    @Test void finishedRunsKeepTheirStateAndDoNotReleaseAnySlot() {
        when(runs.stopForHistoryCleanup(List.of(), NOW)).thenReturn(List.of());
        service.stopFor(List.of(), NOW); verifyNoInteractions(projects, events);
    }

    @Test void repositoryFailurePropagatesWithoutEmittingEvents() {
        UUID id = UUID.randomUUID();
        when(runs.stopForHistoryCleanup(List.of(id), NOW)).thenThrow(new IllegalStateException("write failed"));
        assertThatThrownBy(() -> service.stopFor(List.of(id), NOW)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(projects, events);
    }
}
