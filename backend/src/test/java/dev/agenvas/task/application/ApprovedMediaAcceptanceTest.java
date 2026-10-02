package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.ProviderProperties;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/** All collaborators are mocked; this verifies acceptance boundaries without contacting a Provider. */
class ApprovedMediaAcceptanceTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ARTIFACT = UUID.randomUUID();
    private static final UUID CARD = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID APPROVAL = UUID.randomUUID();
    private static final long DRAFT_VERSION = 3;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskRepository repository = mock(TaskRepository.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final CanvasItemQueryService cards = mock(CanvasItemQueryService.class);
    private final CanvasService canvas = mock(CanvasService.class);
    private final MediaCapabilityService capabilities = mock(MediaCapabilityService.class, Answers.RETURNS_DEEP_STUBS);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final UsageService usage = mock(UsageService.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final dev.agenvas.settings.application.MediaStyleService styles = mock(dev.agenvas.settings.application.MediaStyleService.class);
    private final MediaCapabilityBinding binding = new MediaCapabilityBinding(UUID.randomUUID(), 2,
            UUID.randomUUID(), 4, "MOCK_IMAGE", "a".repeat(64));
    private DirectMediaTaskService service;

    @BeforeEach
    void setUp() {
        service = new DirectMediaTaskService(repository, drafts, artifacts, mock(AssetService.class),
                cards, canvas, capabilities, new ProviderProperties("mock", 1), events, usage,
                mapper, Clock.fixed(NOW, ZoneOffset.UTC), mock(ProjectService.class), runs, styles);
        Artifact artifact = new Artifact(ARTIFACT, PROJECT, Artifact.Kind.IMAGE, "Proposal", null,
                null, 0, NOW, NOW);
        when(artifacts.get(OWNER, PROJECT, ARTIFACT)).thenReturn(new ArtifactService.ArtifactView(artifact, null));
        CanvasItem card = mock(CanvasItem.class);
        when(card.id()).thenReturn(CARD);
        when(card.subjectId()).thenReturn(ARTIFACT);
        when(cards.requireArtifactItem(OWNER, PROJECT, CARD)).thenReturn(card);
        when(drafts.get(OWNER, PROJECT, CARD)).thenReturn(draft("A synthetic landscape", 1));
        when(capabilities.forDraft(binding.capabilityId(), Task.Kind.IMAGE_GENERATION)).thenReturn(binding);
        when(capabilities.resolve(binding.capabilityId(), Task.Kind.IMAGE_GENERATION, 0)).thenReturn(binding);
        when(capabilities.runningHubDefinition(binding)).thenReturn(null);
        when(capabilities.settings(binding)).thenReturn(mapper.createObjectNode());
        when(capabilities.parameters(eq(binding), any())).thenAnswer(call -> call.getArgument(1));
        when(capabilities.inputPolicy(binding)).thenReturn(new MediaAdapterRegistry(List.of()).declaration("MOCK_IMAGE"));
        when(capabilities.capabilitySnapshot(binding.capabilityId()).connectionVersion().originSha256()).thenReturn(null);
        when(canvas.mediaSelectionEpoch(OWNER, PROJECT, CARD)).thenReturn(2L);
        when(events.recordChange(eq(OWNER), eq(PROJECT), any())).thenAnswer(call -> {
            Supplier<?> mutation = call.getArgument(2);
            ProjectEventService.Change<?> change = (ProjectEventService.Change<?>) mutation.get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
        AgentRun run = mock(AgentRun.class);
        when(run.status()).thenReturn(AgentRun.Status.WAITING_TASKS);
        when(runs.get(OWNER, PROJECT, RUN)).thenReturn(run);
    }

    @Test
    void preflightValidatesBatchWithoutCreatingTasksCardsOrReservations() {
        when(drafts.get(OWNER, PROJECT, CARD)).thenReturn(draft("A synthetic landscape", 4));
        var result = service.preflight(OWNER, PROJECT, ARTIFACT, CARD, DRAFT_VERSION);
        assertThat(result.outputCount()).isEqualTo(4);
        assertThat(result.binding()).isEqualTo(binding);
        assertThat(result.safeSummary().path("priceUnknown").asBoolean()).isTrue();
        verify(repository, never()).create(any(), anyList());
        verify(canvas, never()).forkMediaOutputWithinChange(any(), any(), any(), any(), anyLong(), anyInt());
        verifyNoInteractions(usage, events, runs);
    }

    @Test
    void proposalHashChangesWhenPromptOrResultSelectionChanges() {
        String original = service.preflight(OWNER, PROJECT, ARTIFACT, CARD, DRAFT_VERSION).frozenInputHash();
        when(canvas.mediaSelectionEpoch(OWNER, PROJECT, CARD)).thenReturn(3L);
        assertThat(service.preflight(OWNER, PROJECT, ARTIFACT, CARD, DRAFT_VERSION).frozenInputHash()).isNotEqualTo(original);
        when(canvas.mediaSelectionEpoch(OWNER, PROJECT, CARD)).thenReturn(2L);
        when(drafts.get(OWNER, PROJECT, CARD)).thenReturn(draft("A different landscape", 1));
        assertThat(service.preflight(OWNER, PROJECT, ARTIFACT, CARD, DRAFT_VERSION).frozenInputHash()).isNotEqualTo(original);
    }

    @Test
    void staleDraftCannotPassPreflight() {
        assertThatThrownBy(() -> service.preflight(OWNER, PROJECT, ARTIFACT, CARD, DRAFT_VERSION - 1))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("DIRECT_MEDIA_CONFLICT"));
        verifyNoInteractions(usage, events);
    }

    @Test
    void approvedAcceptancePinsRunApprovalTargetAndReservation() {
        Task task = service.runApproved(OWNER, PROJECT, RUN, APPROVAL, ARTIFACT, CARD,
                DRAFT_VERSION, "approved-command");
        assertThat(task.runId()).isEqualTo(RUN);
        assertThat(task.approvedMedia()).isTrue();
        assertThat(task.input().path(Task.APPROVAL_INPUT_PROPERTY).asText()).isEqualTo(APPROVAL.toString());
        assertThat(task.input().path("mediaInput").path("capabilityVersion").asInt()).isEqualTo(binding.capabilityVersion());
        ArgumentCaptor<TaskRepository.ArtifactTarget> target = ArgumentCaptor.forClass(TaskRepository.ArtifactTarget.class);
        verify(repository).createArtifactTarget(target.capture());
        assertThat(target.getValue().canvasItemId()).isEqualTo(CARD);
        verify(repository).findAgentByStepKey(OWNER, PROJECT, RUN, "approved-command");
        verify(repository).bindMediaTask(task.id(), binding);
        verify(usage).reserveMediaTask(OWNER, task, "PROVIDER_UNPRICED");
    }

    @Test
    void approvalCannotAttachAnUnrelatedOccupyingTask() {
        when(repository.findOccupyingDirectMediaTask(PROJECT, CARD)).thenReturn(java.util.Optional.of(mock(Task.class)));
        assertThatThrownBy(() -> service.runApproved(OWNER, PROJECT, RUN, APPROVAL, ARTIFACT, CARD,
                DRAFT_VERSION, "approved-command")).isInstanceOf(ApiProblemException.class);
        verify(repository, never()).create(any(), anyList());
        verifyNoInteractions(usage);
    }

    @Test
    void sameApprovedCommandReusesItsTaskAndRejectsAnotherApproval() {
        Task task = service.runApproved(OWNER, PROJECT, RUN, APPROVAL, ARTIFACT, CARD,
                DRAFT_VERSION, "approved-command");
        when(repository.findAgentByStepKey(OWNER, PROJECT, RUN, "approved-command"))
                .thenReturn(java.util.Optional.of(task));
        assertThat(service.runApproved(OWNER, PROJECT, RUN, APPROVAL, ARTIFACT, CARD,
                DRAFT_VERSION, "approved-command")).isSameAs(task);
        assertThatThrownBy(() -> service.runApproved(OWNER, PROJECT, RUN, UUID.randomUUID(), ARTIFACT, CARD,
                DRAFT_VERSION, "approved-command")).isInstanceOf(ApiProblemException.class);
        verify(repository).create(task, List.of());
        verify(usage).reserveMediaTask(OWNER, task, "PROVIDER_UNPRICED");
    }

    @ParameterizedTest
    @EnumSource(value = Artifact.Kind.class, names = {"VIDEO", "AUDIO"})
    void approvedVideoAndAudioUseTheirExistingTypedAcceptancePipeline(Artifact.Kind kind) {
        Task.Kind taskKind = Task.Kind.valueOf(kind.name() + "_GENERATION");
        MediaCapabilityBinding typedBinding = new MediaCapabilityBinding(binding.connectionId(), 2,
                binding.capabilityId(), 4, "MOCK_" + kind.name(), binding.mappingSha256());
        when(artifacts.get(OWNER, PROJECT, ARTIFACT)).thenReturn(new ArtifactService.ArtifactView(
                new Artifact(ARTIFACT, PROJECT, kind, "Proposal", null, null, 0, NOW, NOW), null));
        when(drafts.get(OWNER, PROJECT, CARD)).thenReturn(new MediaDraft(PROJECT, CARD, "Synthetic media",
                mapper.createObjectNode(), kind == Artifact.Kind.VIDEO ? 5 : null, typedBinding.capabilityId(), null,
                kind == Artifact.Kind.VIDEO ? MediaDraft.VideoInputMode.TEXT : null, List.of(), List.of(),
                MediaDraft.DisplayMode.DRAFT, DRAFT_VERSION, NOW, NOW));
        when(capabilities.forDraft(typedBinding.capabilityId(), taskKind)).thenReturn(typedBinding);
        when(capabilities.resolve(typedBinding.capabilityId(), taskKind, kind == Artifact.Kind.VIDEO ? 5 : 0))
                .thenReturn(typedBinding);
        when(capabilities.runningHubDefinition(typedBinding)).thenReturn(null);
        when(capabilities.settings(typedBinding)).thenReturn(mapper.createObjectNode());
        when(capabilities.parameters(eq(typedBinding), any())).thenAnswer(call -> call.getArgument(1));
        when(capabilities.inputPolicy(typedBinding)).thenReturn(new MediaAdapterRegistry(List.of())
                .declaration(typedBinding.adapterId()));
        assertThat(service.preflight(OWNER, PROJECT, ARTIFACT, CARD, DRAFT_VERSION).kind()).isEqualTo(taskKind);
        Task task = service.runApproved(OWNER, PROJECT, RUN, APPROVAL, ARTIFACT, CARD, DRAFT_VERSION,
                "approved-" + kind.name());
        assertThat(task.kind()).isEqualTo(taskKind);
        assertThat(task.approvedMedia()).isTrue();
        verify(repository).bindMediaTask(task.id(), typedBinding);
    }

    @Test
    void composedStyleCannotExceedComfyPromptLimitDuringPreflight() {
        UUID styleId = UUID.randomUUID();
        MediaCapabilityBinding comfy = new MediaCapabilityBinding(binding.connectionId(), binding.connectionVersion(),
                binding.capabilityId(), binding.capabilityVersion(), "COMFY_IMAGE_V1", binding.mappingSha256());
        String userPrompt = "x".repeat(MediaAdapterRegistry.COMFY_MAX_PROMPT_LENGTH - 10);
        var draft = new MediaDraft(PROJECT, CARD, userPrompt, mapper.createObjectNode(), null, binding.capabilityId(), styleId,
                null, List.of(), List.of(), MediaDraft.DisplayMode.DRAFT, DRAFT_VERSION, NOW, NOW);
        when(drafts.get(OWNER, PROJECT, CARD)).thenReturn(draft);
        when(styles.forGeneration(styleId, Artifact.Kind.IMAGE)).thenReturn(new dev.agenvas.settings.application.MediaStyleService.Snapshot(
                styleId, 0, "Synthetic", "cinematic light"));
        when(capabilities.forDraft(binding.capabilityId(), Task.Kind.IMAGE_GENERATION)).thenReturn(comfy);
        when(capabilities.resolve(binding.capabilityId(), Task.Kind.IMAGE_GENERATION, 0)).thenReturn(comfy);
        when(capabilities.runningHubDefinition(comfy)).thenReturn(null);
        when(capabilities.settings(comfy)).thenReturn(mapper.createObjectNode());
        when(capabilities.parameters(eq(comfy), any())).thenAnswer(call -> call.getArgument(1));
        when(capabilities.inputPolicy(comfy)).thenReturn(new MediaAdapterRegistry(List.of()).declaration("COMFY_IMAGE_V1"));
        assertThatThrownBy(() -> service.preflight(OWNER, PROJECT, ARTIFACT, CARD, DRAFT_VERSION))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class)
                .satisfies(error -> assertThat(((dev.agenvas.shared.error.ApiProblemException) error).code()).isEqualTo("MEDIA_STYLE_PROMPT_TOO_LONG"));
        verify(repository, never()).create(any(), anyList());
    }

    private MediaDraft draft(String prompt, int count) {
        return new MediaDraft(PROJECT, CARD, prompt, mapper.createObjectNode().put("generationCount", count),
                null, binding.capabilityId(), null, null, List.of(), List.of(), MediaDraft.DisplayMode.DRAFT,
                DRAFT_VERSION, NOW, NOW);
    }
}
