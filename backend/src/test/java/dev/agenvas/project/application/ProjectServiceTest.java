package dev.agenvas.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lifecycle validation; real row locks and deletion replay are exercised against PostgreSQL. */
class ProjectServiceTest {
    private final UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-24T00:00:00Z");
    private final ProjectRepository rows = mock(ProjectRepository.class);
    private final ProjectService service = new ProjectService(rows, Clock.fixed(now, ZoneOffset.UTC));

    private void stored(Project.Status status, long version, UUID runId) {
        when(rows.findById(owner, id)).thenReturn(Optional.of(new Project(id, owner, "Project",
                Project.AspectRatio.SQUARE_1_1, status, 0, version, now, now,
                status == Project.Status.ACTIVE ? null : now)));
        when(rows.lockRunSlot(owner, id)).thenReturn(Optional.of(new ProjectRepository.RunSlot(status, runId)));
    }

    @Test
    void deletesAnArchivedProjectWithItsExpectedVersion() {
        stored(Project.Status.ARCHIVED, 2, null);
        when(rows.delete(owner, id, 2, now)).thenReturn(true);
        service.delete(owner, id, 2);
        verify(rows).delete(owner, id, 2, now);
    }

    @Test
    void rejectsActiveProjectsAndRunsWithoutWriting() {
        stored(Project.Status.ACTIVE, 2, null);
        assertCode(() -> service.delete(owner, id, 2), "PROJECT_NOT_ARCHIVED");
        stored(Project.Status.ARCHIVED, 2, UUID.randomUUID());
        assertCode(() -> service.delete(owner, id, 2), "ACTIVE_RUN_EXISTS");
        verify(rows, never()).delete(any(), any(), anyLong(), any());
    }

    @Test
    void conflictsOnStaleVersionsAndFailedCas() {
        stored(Project.Status.ARCHIVED, 2, null);
        assertCode(() -> service.delete(owner, id, 1), "VERSION_CONFLICT");
        assertCode(() -> service.delete(owner, id, 2), "VERSION_CONFLICT");
    }

    @Test
    void replaysOnlyTheVersionThatDeletedTheProject() {
        stored(Project.Status.DELETED, 3, null);
        service.delete(owner, id, 2);
        assertCode(() -> service.delete(owner, id, 1), "VERSION_CONFLICT");
        assertCode(() -> service.delete(owner, id, 3), "VERSION_CONFLICT");
        verify(rows, never()).delete(any(), any(), anyLong(), any());
    }

    @Test
    void hidesDeletedProjectsWhilePreservingExecutionAuditReads() {
        stored(Project.Status.DELETED, 3, null);
        assertCode(() -> service.get(owner, id), "RESOURCE_NOT_FOUND");
        assertThat(service.getRetainedProject(owner, id).status()).isEqualTo(Project.Status.DELETED);
        assertCode(() -> service.requireAvailableRunSlot(owner, id), "RESOURCE_NOT_FOUND");
    }

    @Test
    void hidesNonexistentAndForeignProjects() {
        when(rows.lockRunSlot(owner, id)).thenReturn(Optional.empty());
        assertCode(() -> service.delete(owner, id, 0), "RESOURCE_NOT_FOUND");
        verify(rows, never()).delete(any(), any(), anyLong(), any());
    }

    @Test
    void rejectsNegativeVersionsBeforeLocking() {
        assertCode(() -> service.delete(owner, id, -1), "VALIDATION_ERROR");
        verifyNoInteractions(rows);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiProblemException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
