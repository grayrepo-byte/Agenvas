package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class TaskOutputReadAccessServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), run = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID(), artifact = UUID.randomUUID(), version = UUID.randomUUID(), node = UUID.randomUUID();
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final CanvasItemQueryService items = mock(CanvasItemQueryService.class);
    private final TaskOutputReadAccessService service = new TaskOutputReadAccessService(tasks, items);

    @Test void exactPrimaryAndAdditionalArchivesRequireTheirCurrentOwnedNode() {
        Task task = task(run, true);
        when(task.output()).thenReturn(output());
        assertThat(allows(run)).isTrue();
        var extra = mapper.createObjectNode();
        extra.putArray("additionalResults").add(output().put("canvasItemId", node.toString()));
        when(task.output()).thenReturn(extra);
        assertThat(allows(run)).isTrue();
        when(items.artifactItems(owner, project, artifact)).thenReturn(List.of());
        assertThat(allows(run)).isFalse();
    }

    @Test void sameRunAndTrustedLinkedApprovalFlagAreMandatory() {
        Task task = task(run, true);
        when(task.output()).thenReturn(output());
        assertThat(allows(UUID.randomUUID())).isFalse();
        assertThat(allows(null)).isFalse();
        when(task.input()).thenReturn(mapper.createObjectNode().put("canvasItemId", node.toString()));
        assertThat(allows(run)).isFalse();
        task = task(run, true);
        when(task.output()).thenReturn(output());
        when(task.approvedMedia()).thenReturn(false);
        assertThat(allows(run)).isFalse();
    }

    @Test void modelInputAndSimilarOutputIdsCannotGrantAnUnarchivedVersion() {
        Task task = task(run, true);
        assertThat(allows(run)).isFalse();
        when(task.output()).thenReturn(output().put("artifactVersionId", UUID.randomUUID().toString()));
        assertThat(allows(run)).isFalse();
        when(task.output()).thenReturn(output().put("artifactId", UUID.randomUUID().toString()));
        assertThat(allows(run)).isFalse();
    }

    @Test void exactCanceledLateArchiveIsReadableWithoutReplacingItsOriginalTaskOutcome() {
        Task task = task(run, true);
        when(task.output()).thenReturn(mapper.createObjectNode().put("status", "CANCELED"));
        when(tasks.lateResults(owner, project, taskId)).thenReturn(List.of(output()));
        assertThat(allows(run)).isTrue();
        assertThat(service.archivedOutputs(owner, project, taskId)).hasSize(2);
        assertThat(task.output().path("status").asText()).isEqualTo("CANCELED");
        assertThat(service.archivedOutputs(owner, UUID.randomUUID(), taskId)).isEmpty();
    }

    private Task task(UUID taskRun, boolean explicitSelection) {
        Task task = mock(Task.class);
        when(task.id()).thenReturn(taskId);
        when(task.projectId()).thenReturn(project);
        when(task.runId()).thenReturn(taskRun);
        when(task.approvedMedia()).thenReturn(true);
        when(task.input()).thenReturn(mapper.createObjectNode().put("canvasItemId", node.toString())
                .put(Task.REQUIRE_EXPLICIT_SELECTION_PROPERTY, explicitSelection));
        when(tasks.find(owner, project, taskId)).thenReturn(Optional.of(task));
        CanvasItem item = mock(CanvasItem.class);
        when(item.id()).thenReturn(node);
        when(items.artifactItems(owner, project, artifact)).thenReturn(List.of(item));
        return task;
    }
    private ObjectNode output() { return mapper.createObjectNode().put("artifactId", artifact.toString()).put("artifactVersionId", version.toString()); }
    private boolean allows(UUID taskRun) { return service.allows(owner, project, taskRun, taskId, artifact, version); }
}
