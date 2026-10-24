package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.ProviderFailure;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Application invariants; PostgreSQL tests separately verify row-version and lease fencing. */
class TaskProviderRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final String WORKER = "provider-recovery";
    private final UUID taskId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskRepository repository = mock(TaskRepository.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final TaskService service = new TaskService(mock(AgentRunService.class), mock(ProjectService.class),
            mock(ArtifactService.class), mock(MediaDraftService.class), mock(CanvasService.class), repository,
            new TaskProperties(Duration.ofMinutes(30), 1), events, mock(UsageService.class), mapper,
            Clock.fixed(NOW, ZoneOffset.UTC), new ShutdownGate());

    @BeforeEach void setup() {
        when(repository.ownerId(taskId)).thenReturn(Optional.of(ownerId));
        when(repository.mediaBinding(taskId)).thenReturn(Optional.of(new MediaCapabilityBinding(
                UUID.randomUUID(), 1, UUID.randomUUID(), 1, MediaAdapterRegistry.RUNNINGHUB_IMAGE, "synthetic-hash")));
        when(events.recordChange(eq(ownerId), eq(projectId), any())).thenAnswer(call -> {
            ProjectEventService.Change<?> change = ((Supplier<ProjectEventService.Change<?>>) call.getArgument(2)).get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
    }

    @Test void aPublicDiagnosticPreservesExistingProgressAndDoesNotChangeTaskState() {
        Task current = task(8, mapper.createObjectNode().put("archivedCount", 1));
        when(repository.findById(taskId)).thenReturn(Optional.of(current));
        when(repository.recordProviderFailure(eq(current), eq(WORKER), any(), eq(NOW))).thenReturn(true);
        ProviderFailure failure = new ProviderFailure(ProviderFailure.Stage.POLL,
                ProviderFailure.Category.PARAMETER, 400, 301L, "14", "value");

        assertThat(service.recordProviderFailure(current, WORKER, failure)).isTrue();

        ArgumentCaptor<JsonNode> output = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).recordProviderFailure(eq(current), eq(WORKER), output.capture(), eq(NOW));
        assertThat(output.getValue().path("archivedCount").asInt()).isEqualTo(1);
        assertThat(output.getValue().path("providerFailure")).isEqualTo(mapper.valueToTree(failure));
        verify(repository, never()).finish(any(), any(), anyLong(), any(), any(), any(), any());
        verify(events).append(eq(ownerId), eq(projectId), any());
    }

    @Test void anOldEpochCannotOverwriteDiagnosticsAndDoesNotPreventUnknownRecovery() {
        when(repository.findById(taskId)).thenReturn(Optional.of(task(9, null)));
        assertThat(service.recordProviderFailure(task(8, null), WORKER, new ProviderFailure(
                ProviderFailure.Stage.SUBMIT, ProviderFailure.Category.UNAVAILABLE, 503, null, null, null))).isFalse();
        verify(repository, never()).recordProviderFailure(any(), any(), any(), any());
        verify(events, never()).append(any(), any(), any());
    }

    @Test void onlyMatchingResultUrlsMayBeRefreshed() {
        Task lease = task(8, null);
        var frozen = manifest("https://media.example.test/result.png?signature=old");
        var refreshed = manifest("https://media.example.test/result.png?signature=new");
        when(repository.refreshProviderResultUrls(lease, WORKER, frozen, refreshed, NOW)).thenReturn(true);
        service.refreshProviderResultUrls(lease, WORKER, frozen, refreshed);
        verify(repository).refreshProviderResultUrls(lease, WORKER, frozen, refreshed, NOW);
    }

    @Test void replacingAChangedCheckpointFailsInsteadOfOverwritingNewerRecovery() {
        assertThatThrownBy(() -> service.refreshProviderResultUrls(task(8, null), WORKER,
                manifest("https://media.example.test/result.png?old"), manifest("https://media.example.test/result.png?new")))
                .isInstanceOfSatisfying(ApiProblemException.class, failure -> assertThat(failure.code()).isEqualTo("TASK_LEASE_LOST"));
    }

    @ParameterizedTest @EnumSource(ChangedIdentity.class)
    void aRefreshCannotChangeFrozenResultIdentityOrAccounting(ChangedIdentity changed) {
        var frozen = manifest("https://media.example.test/result.png?old");
        var old = frozen.results().getFirst();
        var result = new ProviderResultManifest.Result(changed == ChangedIdentity.ORDINAL ? 1 : old.ordinal(),
                changed == ChangedIdentity.NODE ? "88" : old.nodeId(),
                changed == ChangedIdentity.KIND ? RunningHubDefinition.OutputKind.VIDEO : old.kind(),
                changed != ChangedIdentity.PRIMARY, "https://media.example.test/result.png?new",
                changed == ChangedIdentity.ARCHIVE ? new ProviderResultManifest.ArchiveEntry("result.png", "a".repeat(64)) : old.archiveEntry(),
                changed == ChangedIdentity.METADATA ? mapper.createObjectNode().put("different", true) : old.providerMetadata());
        var refreshed = new ProviderResultManifest(1, List.of(result),
                changed == ChangedIdentity.USAGE ? mapper.createObjectNode().put("consumeCoins", 99) : frozen.usage());
        assertThatThrownBy(() -> service.refreshProviderResultUrls(task(8, null), WORKER, frozen, refreshed))
                .isInstanceOf(ApiProblemException.class);
        verify(repository, never()).refreshProviderResultUrls(any(), any(), any(), any(), any());
    }

    @Test void anOrdinaryFailureRetainsOnlyTheSafeMediaDiagnostic() {
        var diagnostic = mapper.valueToTree(new ProviderFailure(ProviderFailure.Stage.POLL,
                ProviderFailure.Category.GENERATION, null, 500L, "14", "value"));
        var output = mapper.createObjectNode().put("unfinishedInternalPayload", "synthetic-private");
        output.set("providerFailure", diagnostic);
        Task current = task(8, output);
        when(repository.findById(taskId)).thenReturn(Optional.of(current));
        when(repository.finish(eq(taskId), eq(WORKER), eq(8L), eq(Task.Status.FAILED), any(), any(), eq(NOW)))
                .thenReturn(true);
        service.fail(current, WORKER, "RUNNINGHUB_GENERATION_FAILED");
        ArgumentCaptor<JsonNode> saved = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).finish(eq(taskId), eq(WORKER), eq(8L), eq(Task.Status.FAILED), saved.capture(),
                eq("RUNNINGHUB_GENERATION_FAILED"), eq(NOW));
        assertThat(saved.getValue().size()).isEqualTo(1);
        assertThat(saved.getValue().path("providerFailure")).isEqualTo(diagnostic);
    }

    private ProviderResultManifest manifest(String url) {
        return new ProviderResultManifest(1, List.of(new ProviderResultManifest.Result(0, "9",
                RunningHubDefinition.OutputKind.IMAGE, true, url)), mapper.createObjectNode().put("consumeCoins", 1));
    }

    private Task task(long epoch, JsonNode output) {
        return new Task(taskId, projectId, null, "synthetic-runninghub", Task.Kind.IMAGE_GENERATION,
                Task.Status.RUNNING, false, mapper.createObjectNode(), "synthetic-hash", output,
                "synthetic-provider-task", 1, NOW, WORKER, NOW.plusSeconds(1800), epoch, 3, null, NOW, NOW, null);
    }

    private enum ChangedIdentity { ORDINAL, NODE, KIND, PRIMARY, ARCHIVE, METADATA, USAGE }
}
