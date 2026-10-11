package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Public completion validation and event policy; PostgreSQL separately verifies the atomic archive. */
class TaskMediaCompletionTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID ARTIFACT = UUID.randomUUID();
    private static final String WORKER = "media-completion-worker";
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskRepository repository = mock(TaskRepository.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final CanvasService canvas = mock(CanvasService.class);
    private final UsageService usage = mock(UsageService.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final TaskService service = new TaskService(runs, projects, artifacts,
            mock(MediaDraftService.class), canvas, repository, new TaskProperties(null, 0),
            events, usage, mapper, Clock.fixed(NOW, ZoneOffset.UTC), new ShutdownGate());

    @ParameterizedTest
    @EnumSource(InvalidResults.class)
    void invalidResultListsFailBeforeAccessingPersistence(InvalidResults invalid) {
        ObjectNode content = content(TASK);
        var primary = new TaskService.ArchivedProviderResult(0, Artifact.Kind.IMAGE, true, content);
        List<TaskService.ArchivedProviderResult> results = switch (invalid) {
            case EMPTY -> List.of();
            case MULTIPLE_PRIMARY -> List.of(primary,
                    new TaskService.ArchivedProviderResult(1, Artifact.Kind.IMAGE, true, content));
            case OUT_OF_ORDER -> List.of(new TaskService.ArchivedProviderResult(1, Artifact.Kind.IMAGE, true, content));
            case TEXT_OUTPUT -> List.of(primary,
                    new TaskService.ArchivedProviderResult(1, Artifact.Kind.TEXT, false, content));
            case WRONG_PRIMARY_KIND -> List.of(new TaskService.ArchivedProviderResult(0, Artifact.Kind.AUDIO, true, content));
        };

        assertThatThrownBy(() -> service.succeedWithArtifacts(task(Task.Status.SUBMITTING), WORKER, results, null))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(repository, events, projects, artifacts, canvas, usage, runs);
    }

    @Test
    void aLostLeaseTakesPriorityOverAnInvalidPrimarySource() {
        Task lease = task(Task.Status.SUBMITTING);
        transaction();
        Task current = new Task(lease.id(), lease.projectId(), null, lease.stepKey(), lease.kind(), lease.status(),
                false, lease.input(), lease.inputHash(), null, null, 1, NOW, WORKER,
                NOW, lease.leaseEpoch(), lease.version(), null, NOW, NOW, null);
        when(repository.findById(TASK)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.succeedWithArtifacts(lease, WORKER, List.of(
                        new TaskService.ArchivedProviderResult(0, Artifact.Kind.IMAGE, true, content(UUID.randomUUID()))), null))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("TASK_LEASE_LOST"));
        verifyNoInteractions(artifacts, canvas, usage);
    }

    @ParameterizedTest
    @EnumSource(CostSource.class)
    void completionEventsDistinguishLocalExternalAndUnboundResults(CostSource source) {
        Task lease = task(Task.Status.SUBMITTING);
        transaction();
        Task completed = task(Task.Status.SUCCEEDED);
        when(repository.findById(TASK)).thenReturn(Optional.of(lease), Optional.of(completed));
        ObjectNode content = content(TASK);
        var result = new ArtifactService.TaskVersionResult(UUID.randomUUID(), false);
        when(artifacts.appendTaskVersionWithinChange(OWNER, PROJECT, ARTIFACT, null, null, 0,
                content, null, false)).thenReturn(result);
        when(repository.finishSubmitting(eq(lease), eq(WORKER), any(), eq(NOW))).thenReturn(true);
        Optional<MediaCapabilityBinding> binding = source == CostSource.UNBOUND ? Optional.empty()
                : Optional.of(new MediaCapabilityBinding(UUID.randomUUID(), 1, UUID.randomUUID(), 1,
                        source == CostSource.LOCAL ? MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR
                                : MediaAdapterRegistry.RUNNINGHUB_IMAGE, "synthetic-mapping"));
        when(repository.mediaBinding(TASK)).thenReturn(binding);

        assertThat(service.succeedWithArtifacts(lease, WORKER, List.of(
                new TaskService.ArchivedProviderResult(0, Artifact.Kind.IMAGE, true, content)), null)).isEqualTo(result);

        var outcome = ArgumentCaptor.forClass(ProjectEventService.EventDraft.class);
        var order = inOrder(repository, usage, events);
        order.verify(repository).finishSubmitting(eq(lease), eq(WORKER), any(), eq(NOW));
        order.verify(repository).clearProviderPollFailures(TASK);
        order.verify(usage).settleMediaTask(OWNER, lease);
        order.verify(repository).findById(TASK);
        order.verify(events).append(eq(OWNER), eq(PROJECT), outcome.capture());
        assertThat(outcome.getValue().type()).isEqualTo("task.status.changed");
        assertThat(outcome.getValue().aggregateVersion()).isEqualTo(completed.version());
        assertThat(outcome.getValue().payload().path("possibleExternalCost").asBoolean())
                .isEqualTo(source != CostSource.LOCAL);
        assertThat(outcome.getValue().payload().path("artifactVersionId").asText()).isEqualTo(result.versionId().toString());
        verify(repository).mediaBinding(TASK);
    }

    @ParameterizedTest
    @ValueSource(strings = {NativeMediaProtocol.PROTOCOL_ID, KlingModels.PROTOCOL_ID, ""})
    void resultCountLimitsStaySpecificToTheFrozenProtocol(String protocol) {
        Task lease = task(Task.Status.SUBMITTING);
        ((ObjectNode) lease.input()).put("providerProtocol", protocol);
        int count = NativeMediaProtocol.PROTOCOL_ID.equals(protocol) ? 18 : 17;
        var results = IntStream.range(0, count).mapToObj(index ->
                new TaskService.ArchivedProviderResult(index, Artifact.Kind.IMAGE, index == 0, content(TASK))).toList();

        assertThatThrownBy(() -> service.succeedWithArtifacts(lease, WORKER, results, null))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(repository, events, projects, artifacts, canvas, usage, runs);
    }

    @Test
    void nativeSeventeenOutputsReachTheRefactoredArchiveAndSettleTheirActualCount() {
        Task lease = task(Task.Status.SUBMITTING);
        ((ObjectNode) lease.input()).put("providerProtocol", NativeMediaProtocol.PROTOCOL_ID);
        prepareSuccessfulArchive(lease);
        var results = IntStream.range(0, 17).mapToObj(index ->
                new TaskService.ArchivedProviderResult(index, Artifact.Kind.IMAGE, index == 8, content(TASK))).toList();

        service.succeedWithArtifacts(lease, WORKER, results, null);

        verify(artifacts, times(17)).appendTaskVersionWithinChange(eq(OWNER), eq(PROJECT), eq(ARTIFACT),
                isNull(), isNull(), eq(0L), any(), nullable(JsonNode.class), eq(false));
        verify(usage).settleMediaTask(OWNER, lease, 17, null);
        var output = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).finishSubmitting(eq(lease), eq(WORKER), output.capture(), eq(NOW));
        assertThat(output.getValue().path("additionalResults")).hasSize(16);
        assertThat(output.getValue().path("additionalResults").get(7).path("ordinal").asInt()).isEqualTo(7);
        assertThat(output.getValue().path("additionalResults").get(8).path("ordinal").asInt()).isEqualTo(9);
    }

    @ParameterizedTest
    @ValueSource(strings = {NativeMediaProtocol.PROTOCOL_ID, KlingModels.PROTOCOL_ID})
    void actualVideoSecondsSurviveCompletionRefactoringAndIncludeFrozenWanInput(String protocol) {
        ObjectNode input = mapper.createObjectNode().put("providerProtocol", protocol);
        if (NativeMediaProtocol.PROTOCOL_ID.equals(protocol)) input.put("nativeBillableInputSeconds", new BigDecimal("3.250"));
        Task lease = task(Task.Status.SUBMITTING, Task.Kind.VIDEO_GENERATION, input);
        prepareSuccessfulArchive(lease);
        ObjectNode video = content(TASK);
        video.putObject("parameters").put("providerResultDurationMs", 5125);

        service.succeedWithArtifacts(lease, WORKER, List.of(
                new TaskService.ArchivedProviderResult(0, Artifact.Kind.VIDEO, true, video)), null);

        BigDecimal expected = new BigDecimal(NativeMediaProtocol.PROTOCOL_ID.equals(protocol) ? "8.375" : "5.125");
        var order = inOrder(repository, usage);
        order.verify(repository).finishSubmitting(eq(lease), eq(WORKER), any(), eq(NOW));
        order.verify(repository).clearProviderPollFailures(TASK);
        order.verify(usage).settleMediaTask(OWNER, lease, 0, expected);
    }

    private void prepareSuccessfulArchive(Task lease) {
        transaction();
        when(repository.findById(TASK)).thenReturn(Optional.of(lease),
                Optional.of(task(Task.Status.SUCCEEDED, lease.kind(), (ObjectNode) lease.input())));
        when(repository.finishSubmitting(eq(lease), eq(WORKER), any(), eq(NOW))).thenReturn(true);
        Artifact.Kind kind = Artifact.Kind.valueOf(lease.kind().name().replace("_GENERATION", ""));
        when(artifacts.get(OWNER, PROJECT, ARTIFACT)).thenReturn(new ArtifactService.ArtifactView(
                new Artifact(ARTIFACT, PROJECT, kind, "Synthetic outputs", null, null, 0, NOW, NOW), null));
        when(artifacts.appendTaskVersionWithinChange(eq(OWNER), eq(PROJECT), eq(ARTIFACT), isNull(), isNull(),
                eq(0L), any(), nullable(JsonNode.class), eq(false)))
                .thenAnswer(call -> new ArtifactService.TaskVersionResult(UUID.randomUUID(), false));
    }

    private void transaction() {
        when(repository.ownerId(TASK)).thenReturn(Optional.of(OWNER));
        when(repository.findArtifactTarget(TASK)).thenReturn(Optional.of(
                new TaskRepository.ArtifactTarget(TASK, PROJECT, ARTIFACT, null, 0, null)));
        when(projects.get(OWNER, PROJECT)).thenReturn(new Project(PROJECT, OWNER, "Synthetic project",
                Project.AspectRatio.SQUARE_1_1, Project.Status.ACTIVE, 0, 0, NOW, NOW, null));
        when(events.recordChange(eq(OWNER), eq(PROJECT), any())).thenAnswer(call -> {
            Supplier<?> mutation = call.getArgument(2);
            var change = (ProjectEventService.Change<?>) mutation.get();
            assertThat(change.event()).isNull();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
    }

    private Task task(Task.Status status) {
        return task(status, Task.Kind.IMAGE_GENERATION, mapper.createObjectNode());
    }

    private Task task(Task.Status status, Task.Kind kind, ObjectNode input) {
        boolean submitting = status == Task.Status.SUBMITTING;
        return new Task(TASK, PROJECT, null, "synthetic-completion", kind, status,
                false, input, "synthetic-hash", null, null, 1, NOW,
                submitting ? WORKER : null, submitting ? NOW.plusSeconds(300) : null, 5, submitting ? 3 : 4,
                null, NOW, NOW, submitting ? null : NOW);
    }

    private ObjectNode content(UUID taskId) {
        return mapper.createObjectNode().put("sourceTaskId", taskId.toString());
    }

    private enum InvalidResults { EMPTY, MULTIPLE_PRIMARY, OUT_OF_ORDER, TEXT_OUTPUT, WRONG_PRIMARY_KIND }
    private enum CostSource { LOCAL, EXTERNAL, UNBOUND }
}
