package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.llm.application.ReadToolService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Tool arguments and Run scope remain enforced even when a task belongs to the project. */
class ReadToolServiceTest {

    private final TaskService tasks = mock(TaskService.class);
    private final ReadToolService reader = new ReadToolService(
            mock(ProjectService.class), mock(ArtifactService.class), tasks,
            new ObjectMapper());
    private final TrustedToolContext context = new TrustedToolContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void rejectsAnotherRunTaskWithoutReturningItsStatus() {
        UUID taskId = UUID.randomUUID();
        Task foreignRunTask = mock(Task.class);
        when(tasks.get(context.ownerId(), context.projectId(), taskId))
                .thenReturn(foreignRunTask);
        when(foreignRunTask.runId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> reader.taskStatus(context, UUID.randomUUID(),
                "{\"taskIds\":[\"" + taskId + "\"]}"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("TASK_NOT_FOUND");
    }

    @Test
    void rejectsOversizedAndDuplicateListsBeforeReadingAnyTask() {
        String oneId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> reader.taskStatus(context, UUID.randomUUID(),
                "{\"taskIds\":[\"" + oneId + "\",\"" + oneId + "\"]}"))
                .isInstanceOf(ApiProblemException.class);
        String thirteen = (",\"" + oneId + "\"").repeat(12);
        assertThatThrownBy(() -> reader.taskStatus(context, UUID.randomUUID(),
                "{\"taskIds\":[\"" + oneId + "\"" + thirteen + "]}"))
                .isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(tasks);
    }
}
