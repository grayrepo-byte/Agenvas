package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class LocalCompositionRetryTest {
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(Task.Initiator.class)
    void explicitRetryKeepsExactInputsButBecomesAZeroCostUserDelegation(Task.Initiator initiator) {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), taskId = UUID.randomUUID(), artifactId = UUID.randomUUID();
        var repository = mock(TaskRepository.class);
        var tasks = mock(TaskService.class);
        var catalog = mock(MediaCapabilityService.class);
        var artifacts = mock(ArtifactService.class);
        var usage = mock(UsageService.class);
        var events = mock(ProjectEventService.class);
        var service = new ManualUnknownRetryService(tasks, repository, catalog, mock(ProjectService.class), artifacts,
                usage, events, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        var input = mapper.createObjectNode().put("durationSeconds", 3).put("sourceDurationMs", 3000);
        input.putObject("videoComposition").putArray("shots").addObject().put("videoVersionId", UUID.randomUUID().toString());
        input.putObject("compositionOrigin").put("runId", "original-source");
        if (initiator == Task.Initiator.BUILTIN_AGENT) {
            String runId = UUID.randomUUID().toString();
            input.put("compositionRunId", runId);
            input.putObject("agentLocalOrigin").put("runId", runId).put("operationId", UUID.randomUUID().toString());
        }
        if (initiator == Task.Initiator.EXTERNAL_MCP) input.put("initiator", "EXTERNAL_MCP")
                .put("mcpTokenId", UUID.randomUUID().toString()).put("mcpOperationId", UUID.randomUUID().toString());
        var original = new Task(taskId, project, null, "original", Task.Kind.VIDEO_GENERATION, Task.Status.UNKNOWN,
                false, input, Sha256.hex(input.toString()), null, null, 1, NOW, null, null, 1, 2, null, NOW, NOW, null);
        var binding = new MediaCapabilityBinding(UUID.randomUUID(), 1, UUID.randomUUID(), 1,
                MediaAdapterRegistry.LOCAL_VIDEO_COMPOSITOR, "a".repeat(64));
        when(tasks.get(owner, project, taskId)).thenReturn(original);
        when(repository.mediaBinding(taskId)).thenReturn(Optional.of(binding));
        when(catalog.isCurrentBinding(binding, Task.Kind.VIDEO_GENERATION, 3)).thenReturn(true);
        when(repository.findArtifactTarget(taskId)).thenReturn(Optional.of(new TaskRepository.ArtifactTarget(taskId,
                project, artifactId, null, 0, UUID.randomUUID())));
        var artifact = mock(Artifact.class);
        when(artifacts.get(owner, project, artifactId)).thenReturn(new ArtifactService.ArtifactView(artifact, null));
        when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(call -> {
            Supplier<?> mutation = call.getArgument(2);
            var change = (ProjectEventService.Change<?>) mutation.get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });

        Task replacement = service.create(owner, project, taskId, original.version(), "explicit-retry");

        assertThat(replacement.initiator()).isEqualTo(Task.Initiator.USER_DIRECT);
        assertThat(replacement.compositionRunId()).isNull();
        assertThat(replacement.agentLocalRunId()).isNull();
        assertThat(replacement.input().has("agentLocalOrigin")).isFalse();
        assertThat(replacement.input().has("mcpOperationId")).isFalse();
        assertThat(replacement.input().at("/compositionOrigin/runId").isNull()).isTrue();
        assertThat(replacement.input().path("videoComposition")).isEqualTo(input.path("videoComposition"));
        assertThat(replacement.inputHash()).isEqualTo(Sha256.hex(replacement.input().toString()));
        verify(usage).reserveMediaTask(owner, replacement, "LOCAL_NO_COST", 1);
        verify(repository).createManualReplacement(new TaskRepository.ManualReplacement(project, taskId,
                replacement.id(), owner, original.version(), "explicit-retry", NOW));
        var published = ArgumentCaptor.forClass(ProjectEventService.EventDraft.class);
        verify(events, times(2)).append(eq(owner), eq(project), published.capture());
        assertThat(published.getAllValues()).allSatisfy(event ->
                assertThat(event.payload().path("possibleExternalCost").asBoolean()).isFalse());
    }
}
