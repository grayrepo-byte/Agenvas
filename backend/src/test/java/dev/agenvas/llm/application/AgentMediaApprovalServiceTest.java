package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.domain.AgentMediaApproval;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Uses fake services to verify consent gating, immutable batch checks, and decision replay. */
class AgentMediaApprovalServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private final UUID ownerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();
    private final UUID canvasItemId = UUID.randomUUID();
    private final JsonMapper mapper = new JsonMapper();
    private final AgentMediaApprovalRepository approvals = mock(AgentMediaApprovalRepository.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final CanvasService canvas = mock(CanvasService.class);
    private final CanvasConnectionService connections = mock(CanvasConnectionService.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final MediaCapabilityService capabilities = mock(MediaCapabilityService.class);
    private final DirectMediaTaskService mediaTasks = mock(DirectMediaTaskService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final MediaCapabilityBinding binding = new MediaCapabilityBinding(UUID.randomUUID(),
            1, UUID.randomUUID(), 1, "mock-image", "synthetic-mapping");
    private final AgentMediaApprovalService service = new AgentMediaApprovalService(approvals,
            runs, artifacts, canvas, drafts, capabilities, mediaTasks, events, publisher,
            mapper, Clock.fixed(NOW, ZoneOffset.UTC), mock(ToolExecutionRepository.class), connections);

    @BeforeEach
    void lockedProjectMutationExecutes() {
        when(events.recordChange(eq(ownerId), eq(projectId), any())).thenAnswer(invocation -> {
            Supplier<ProjectEventService.Change<?>> mutation = invocation.getArgument(2);
            return new ProjectEventService.RecordedChange<>(mutation.get().value(), null);
        });
        when(runs.get(ownerId, projectId, runId)).thenReturn(run(AgentRun.Status.WAITING_TASKS, 2));
        when(approvals.update(any(), anyLong())).thenReturn(true);
        when(mediaTasks.lockApprovalBinding(any())).thenReturn(true);
    }

    @Test
    void proposalCreatesOnlyDraftAndFreezesPreflight() {
        AgentRun run = run(AgentRun.Status.RUNNING, 1);
        when(runs.get(ownerId, projectId, runId)).thenReturn(run);
        when(approvals.findByToolCall(projectId, runId, 1, "call-media"))
                .thenReturn(Optional.empty());
        Artifact artifact = mock(Artifact.class);
        when(artifact.id()).thenReturn(artifactId);
        when(artifacts.create(ownerId, projectId, Artifact.Kind.IMAGE, "Image", null))
                .thenReturn(new ArtifactService.ArtifactView(artifact, null));
        CanvasItem item = mock(CanvasItem.class);
        when(item.id()).thenReturn(canvasItemId);
        when(item.subjectId()).thenReturn(artifactId);
        when(canvas.placeArtifactsInAgentOutputWithinChange(ownerId, projectId,
                run.agentInstanceId(), List.of(artifactId)))
                .thenReturn(new CanvasService.OutputPlacements(List.of(item), List.of()));
        when(capabilities.forDraft(null, Task.Kind.IMAGE_GENERATION)).thenReturn(binding);
        MediaDraft initial = mock(MediaDraft.class);
        MediaDraft saved = mock(MediaDraft.class);
        when(saved.version()).thenReturn(1L);
        when(drafts.get(ownerId, projectId, canvasItemId)).thenReturn(initial);
        when(drafts.save(eq(ownerId), eq(projectId), eq(canvasItemId), eq(0L), eq("Draw a tree"),
                any(), eq(null), eq(binding.capabilityId()), eq(null), anyList(), anyList(), eq(null)))
                .thenReturn(saved);
        when(mediaTasks.preflight(ownerId, projectId, artifactId, canvasItemId, 1))
                .thenReturn(preflight("frozen-hash"));
        when(approvals.insert(any())).thenReturn(true);

        var result = service.propose(new TrustedToolContext(ownerId, projectId, runId), run,
                UUID.randomUUID(), 1, "call-media",
                "{\"outputs\":[{\"kind\":\"IMAGE\",\"title\":\"Image\",\"prompt\":\"Draw a tree\"}]}");

        ArgumentCaptor<AgentMediaApproval> captured = ArgumentCaptor.forClass(AgentMediaApproval.class);
        verify(approvals).insert(captured.capture());
        assertThat(captured.getValue().status()).isEqualTo(AgentMediaApproval.Status.PENDING);
        assertThat(captured.getValue().taskIds()).isEmpty();
        assertThat(captured.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(86_400));
        assertThat(captured.getValue().targets().path("outputs").get(0)
                .path("frozenInputHash").asText()).isEqualTo("frozen-hash");
        assertThat(result.path("awaitingMedia").asBoolean()).isTrue();
        verify(mediaTasks, never()).runApproved(any(), any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void videoStartEndModeAndExactFrameRolesReachTheDraftService() {
        AgentRun run = run(AgentRun.Status.RUNNING, 1);
        when(runs.get(ownerId, projectId, runId)).thenReturn(run);
        when(approvals.findByToolCall(projectId, runId, 1, "call-media"))
                .thenReturn(Optional.empty());
        Artifact artifact = mock(Artifact.class);
        when(artifact.id()).thenReturn(artifactId);
        when(artifacts.create(ownerId, projectId, Artifact.Kind.VIDEO, "Video", null))
                .thenReturn(new ArtifactService.ArtifactView(artifact, null));
        CanvasItem item = mock(CanvasItem.class);
        when(item.id()).thenReturn(canvasItemId);
        when(item.subjectId()).thenReturn(artifactId);
        when(canvas.placeArtifactsInAgentOutputWithinChange(ownerId, projectId,
                run.agentInstanceId(), List.of(artifactId)))
                .thenReturn(new CanvasService.OutputPlacements(List.of(item), List.of()));
        when(capabilities.forDraft(null, Task.Kind.VIDEO_GENERATION)).thenReturn(binding);
        MediaDraft initial = mock(MediaDraft.class);
        MediaDraft saved = mock(MediaDraft.class);
        when(saved.version()).thenReturn(1L);
        when(drafts.get(ownerId, projectId, canvasItemId)).thenReturn(initial);
        when(drafts.save(eq(ownerId), eq(projectId), eq(canvasItemId), eq(0L), eq("Animate"),
                any(), eq(5), eq(binding.capabilityId()), eq(MediaDraft.VideoInputMode.START_END),
                anyList(), anyList(), eq(null))).thenReturn(saved);
        when(mediaTasks.preflight(ownerId, projectId, artifactId, canvasItemId, 3))
                .thenReturn(new DirectMediaTaskService.MediaPreflight(Task.Kind.VIDEO_GENERATION,
                        binding, "video-frozen-hash", 1, mapper.createObjectNode()));
        when(approvals.insert(any())).thenReturn(true);
        UUID startFrame = UUID.randomUUID();
        UUID endFrame = UUID.randomUUID();
        UUID startArtifact = UUID.randomUUID();
        UUID endArtifact = UUID.randomUUID();
        UUID startCardId = UUID.randomUUID();
        UUID endCardId = UUID.randomUUID();
        when(saved.mediaInputs()).thenReturn(List.of(
                new MediaDraft.MediaInput(startFrame, startArtifact, MediaDraft.InputRole.START_FRAME, 0, "#7C3AED", List.of()),
                new MediaDraft.MediaInput(endFrame, endArtifact, MediaDraft.InputRole.END_FRAME, 1, "#7C3AED", List.of())));
        CanvasItem startCard = mock(CanvasItem.class);
        CanvasItem endCard = mock(CanvasItem.class);
        when(startCard.id()).thenReturn(startCardId);
        when(endCard.id()).thenReturn(endCardId);
        when(canvas.ensureMediaReferenceWithinChange(ownerId, projectId, run.agentInstanceId(), startArtifact, startFrame)).thenReturn(startCard);
        when(canvas.ensureMediaReferenceWithinChange(ownerId, projectId, run.agentInstanceId(), endArtifact, endFrame)).thenReturn(endCard);
        MediaDraft firstConnected = mock(MediaDraft.class);
        MediaDraft finalConnected = mock(MediaDraft.class);
        when(firstConnected.version()).thenReturn(2L);
        when(finalConnected.version()).thenReturn(3L);
        when(connections.connectPreparedMediaInputWithinChange(ownerId, projectId, startCardId, canvasItemId, startFrame, 1))
                .thenReturn(new CanvasConnectionService.ConnectionResult(null, firstConnected, null));
        when(connections.connectPreparedMediaInputWithinChange(ownerId, projectId, endCardId, canvasItemId, endFrame, 2))
                .thenReturn(new CanvasConnectionService.ConnectionResult(null, finalConnected, null));
        String arguments = "{\"outputs\":[{\"kind\":\"VIDEO\",\"title\":\"Video\",\"prompt\":\"Animate\","
                + "\"durationSeconds\":5,\"videoInputMode\":\"START_END\",\"mediaInputs\":["
                + "{\"versionId\":\"" + startFrame + "\",\"role\":\"START_FRAME\"},"
                + "{\"versionId\":\"" + endFrame + "\",\"role\":\"END_FRAME\"}]}]}";

        service.propose(new TrustedToolContext(ownerId, projectId, runId), run,
                UUID.randomUUID(), 1, "call-media", arguments);

        verify(drafts).save(ownerId, projectId, canvasItemId, 0L, "Animate",
                mapper.createObjectNode(), 5, binding.capabilityId(), MediaDraft.VideoInputMode.START_END,
                List.of(new MediaDraftService.SaveMediaInput(startFrame, MediaDraft.InputRole.START_FRAME,
                                "#7C3AED"),
                        new MediaDraftService.SaveMediaInput(endFrame, MediaDraft.InputRole.END_FRAME,
                                "#7C3AED")), List.of(), null);
        ArgumentCaptor<AgentMediaApproval> captured = ArgumentCaptor.forClass(AgentMediaApproval.class);
        verify(approvals).insert(captured.capture());
        assertThat(captured.getValue().targets().at("/outputs/0/draftVersion").asLong()).isEqualTo(3);
        var ordered = inOrder(connections, mediaTasks);
        ordered.verify(connections).connectPreparedMediaInputWithinChange(ownerId, projectId, startCardId, canvasItemId, startFrame, 1);
        ordered.verify(connections).connectPreparedMediaInputWithinChange(ownerId, projectId, endCardId, canvasItemId, endFrame, 2);
        ordered.verify(mediaTasks).preflight(ownerId, projectId, artifactId, canvasItemId, 3);
        assertThat(captured.getValue().request().path("outputs").get(0)
                .path("videoInputMode").asText()).isEqualTo("START_END");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"UNSUPPORTED\"", "1", "null"})
    void invalidVideoModeIsRejectedBeforeCreatingAnArtifact(String suppliedMode) {
        AgentRun run = run(AgentRun.Status.RUNNING, 1);
        when(runs.get(ownerId, projectId, runId)).thenReturn(run);
        when(approvals.findByToolCall(projectId, runId, 1, "call-media"))
                .thenReturn(Optional.empty());
        String arguments = "{\"outputs\":[{\"kind\":\"VIDEO\",\"title\":\"Video\","
                + "\"prompt\":\"Animate\",\"videoInputMode\":" + suppliedMode + "}]}";
        assertThatThrownBy(() -> service.propose(new TrustedToolContext(ownerId, projectId, runId),
                run, UUID.randomUUID(), 1, "call-media", arguments))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("TOOL_ARGUMENT_INVALID"));
        verifyNoInteractions(artifacts, drafts, canvas);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "AUDIO"})
    void nonVideoProposalCannotSetVideoInputMode(String kind) {
        AgentRun run = run(AgentRun.Status.RUNNING, 1);
        when(runs.get(ownerId, projectId, runId)).thenReturn(run);
        when(approvals.findByToolCall(projectId, runId, 1, "call-media"))
                .thenReturn(Optional.empty());
        String arguments = "{\"outputs\":[{\"kind\":\"" + kind + "\",\"title\":\"Media\","
                + "\"prompt\":\"Generate\",\"videoInputMode\":\"TEXT\"}]}";
        assertThatThrownBy(() -> service.propose(new TrustedToolContext(ownerId, projectId, runId),
                run, UUID.randomUUID(), 1, "call-media", arguments))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("TOOL_ARGUMENT_INVALID"));
        verifyNoInteractions(artifacts, drafts, canvas);
    }

    @Test
    void approveChecksEveryOutputBeforeCreatingAnyTask() {
        AgentMediaApproval approval = pending(NOW.plusSeconds(60), 2);
        when(approvals.findForUpdate(projectId, runId, approvalId)).thenReturn(Optional.of(approval));
        when(mediaTasks.preflight(eq(ownerId), eq(projectId), any(), any(), eq(1L)))
                .thenReturn(preflight("frozen-hash"));
        Task first = mock(Task.class);
        Task second = mock(Task.class);
        when(first.id()).thenReturn(UUID.randomUUID());
        when(second.id()).thenReturn(UUID.randomUUID());
        when(mediaTasks.runApproved(eq(ownerId), eq(projectId), eq(runId), eq(approvalId),
                any(), any(), eq(1L), any())).thenReturn(first, second);

        var result = service.decide(ownerId, projectId, runId, approvalId, 0,
                AgentMediaApprovalService.Decision.APPROVE, "approve-command");

        var order = inOrder(mediaTasks, approvals);
        order.verify(mediaTasks).preflight(ownerId, projectId, artifactId, canvasItemId, 1);
        JsonNodeIds secondIds = ids(approval.targets().path("outputs").get(1));
        order.verify(mediaTasks).preflight(ownerId, projectId, secondIds.artifactId(), secondIds.itemId(), 1);
        order.verify(mediaTasks).runApproved(ownerId, projectId, runId, approvalId,
                artifactId, canvasItemId, 1, "agent-media:" + approvalId + ":0");
        order.verify(mediaTasks).runApproved(ownerId, projectId, runId, approvalId,
                secondIds.artifactId(), secondIds.itemId(), 1, "agent-media:" + approvalId + ":1");
        order.verify(approvals).update(any(), eq(0L));
        assertThat(result.status()).isEqualTo(AgentMediaApproval.Status.APPROVED);
        assertThat(result.taskIds()).containsExactly(first.id(), second.id());
        assertThat(result.executionDeadline()).isEqualTo(NOW.plusSeconds(86_400));
        verify(publisher).publishEvent(new AgentMediaApprovalChanged(ownerId, projectId, runId, approvalId));
    }

    @Test
    void skillApprovalLocksTheStyleBeforeRevalidatingAndAcceptingItsExactSource() {
        AgentMediaApproval approval = pending(NOW.plusSeconds(60), 1);
        ObjectNode source = mapper.createObjectNode().put("schemaVersion", 1)
                .put("skillVersionId", UUID.randomUUID().toString()).put("bundleHash", "synthetic-skill-bundle");
        ((ObjectNode) approval.request()).set("creativeSkill", source);
        UUID styleId = UUID.randomUUID();
        ObjectNode preview = mapper.createObjectNode().put("styleId", styleId.toString())
                .put("styleName", "Synthetic watercolor").put("styleVersion", 2);
        preview.set("creativeSkill", source.deepCopy());
        ((ObjectNode) approval.targets().path("outputs").get(0)).set("preview", preview);
        when(approvals.findForUpdate(projectId, runId, approvalId)).thenReturn(Optional.of(approval));
        when(mediaTasks.preflightApproved(ownerId, projectId, artifactId, canvasItemId, 1, source))
                .thenReturn(new DirectMediaTaskService.MediaPreflight(Task.Kind.IMAGE_GENERATION,
                        binding, "frozen-hash", 1, preview));
        Task task = mock(Task.class);
        when(task.id()).thenReturn(UUID.randomUUID());
        when(mediaTasks.runApproved(ownerId, projectId, runId, approvalId, artifactId,
                canvasItemId, 1, "agent-media:" + approvalId + ":0", source)).thenReturn(task);

        var result = service.decide(ownerId, projectId, runId, approvalId, 0,
                AgentMediaApprovalService.Decision.APPROVE, "skill-style-approve");

        var order = inOrder(mediaTasks, approvals);
        order.verify(mediaTasks).lockApprovalStyle(ownerId, projectId, canvasItemId);
        order.verify(mediaTasks).lockApprovalBinding(binding);
        order.verify(mediaTasks).preflightApproved(ownerId, projectId, artifactId, canvasItemId, 1, source);
        order.verify(mediaTasks).runApproved(ownerId, projectId, runId, approvalId, artifactId,
                canvasItemId, 1, "agent-media:" + approvalId + ":0", source);
        ArgumentCaptor<AgentMediaApproval> captured = ArgumentCaptor.forClass(AgentMediaApproval.class);
        order.verify(approvals).update(captured.capture(), eq(0L));
        assertThat(result.status()).isEqualTo(AgentMediaApproval.Status.APPROVED);
        assertThat(captured.getValue().request().path("creativeSkill")).isEqualTo(source);
        assertThat(result.outputs()).hasSize(1);
        assertThat(result.outputs().getFirst().preview().path("creativeSkill")).isEqualTo(source);
        assertThat(result.outputs().getFirst().preview().path("styleId").asText())
                .isEqualTo(styleId.toString());
        assertThat(result.taskIds()).containsExactly(task.id());
    }

    @Test
    void changedStylePreflightRejectsTheSkillBatchBeforeCreatingAnyTask() {
        AgentMediaApproval approval = pending(NOW.plusSeconds(60), 1);
        ObjectNode source = mapper.createObjectNode().put("schemaVersion", 1)
                .put("skillVersionId", UUID.randomUUID().toString());
        ((ObjectNode) approval.request()).set("creativeSkill", source);
        when(approvals.findForUpdate(projectId, runId, approvalId)).thenReturn(Optional.of(approval));
        when(mediaTasks.preflightApproved(ownerId, projectId, artifactId, canvasItemId, 1, source))
                .thenReturn(preflight("changed-style-hash"));

        assertThatThrownBy(() -> service.decide(ownerId, projectId, runId, approvalId, 0,
                AgentMediaApprovalService.Decision.APPROVE, "changed-skill-style"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("AGENT_MEDIA_APPROVAL_STALE"));
        verify(mediaTasks).lockApprovalStyle(ownerId, projectId, canvasItemId);
        verify(mediaTasks, never()).runApproved(any(), any(), any(), any(), any(), any(), anyLong(), any(), any());
        verify(approvals, never()).update(any(), anyLong());
    }

    @Test
    void changedSecondOutputPreventsTheEntireBatch() {
        AgentMediaApproval approval = pending(NOW.plusSeconds(60), 2);
        when(approvals.findForUpdate(projectId, runId, approvalId)).thenReturn(Optional.of(approval));
        when(mediaTasks.preflight(eq(ownerId), eq(projectId), any(), any(), eq(1L)))
                .thenReturn(preflight("frozen-hash"), preflight("changed-hash"));

        assertThatThrownBy(() -> service.decide(ownerId, projectId, runId, approvalId, 0,
                AgentMediaApprovalService.Decision.APPROVE, "approve-command"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("AGENT_MEDIA_APPROVAL_STALE"));
        verify(mediaTasks, never()).runApproved(any(), any(), any(), any(), any(), any(), anyLong(), any());
        verify(approvals, never()).update(any(), anyLong());
    }

    @Test
    void rejectRecordsTerminalResultAndNotifiesWithoutAProviderTask() {
        when(approvals.findForUpdate(projectId, runId, approvalId))
                .thenReturn(Optional.of(pending(NOW.plusSeconds(60), 1)));
        var result = service.decide(ownerId, projectId, runId, approvalId, 0,
                AgentMediaApprovalService.Decision.REJECT, "reject-command");
        assertThat(result.status()).isEqualTo(AgentMediaApproval.Status.REJECTED);
        assertThat(result.result().path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(result.result().path("errorCode").asText()).isEqualTo("AGENT_MEDIA_APPROVAL_REJECTED");
        assertThat(result.result().path("tasks").isEmpty()).isTrue();
        verifyNoInteractions(mediaTasks);
        verify(publisher).publishEvent(new AgentMediaApprovalChanged(ownerId, projectId, runId, approvalId));
    }

    @Test
    void expiredApprovalRecordsAnOutcomeInsteadOfCreatingTasks() {
        when(approvals.findForUpdate(projectId, runId, approvalId))
                .thenReturn(Optional.of(pending(NOW, 1)));
        var result = service.decide(ownerId, projectId, runId, approvalId, 0,
                AgentMediaApprovalService.Decision.APPROVE, "approve-command");
        assertThat(result.status()).isEqualTo(AgentMediaApproval.Status.EXPIRED);
        assertThat(result.result().path("errorCode").asText()).isEqualTo("AGENT_MEDIA_APPROVAL_EXPIRED");
        verifyNoInteractions(mediaTasks);
    }

    @Test
    void originalDecisionReplaysAfterTaskCompletionAndChangedPayloadConflicts() {
        AgentMediaApproval completed = pending(NOW.plusSeconds(60), 1).transition(
                AgentMediaApproval.Status.SUCCEEDED, List.of(UUID.randomUUID()),
                mapper.createObjectNode().put("schemaVersion", 1), NOW.plusSeconds(60),
                "approve-command", Sha256.hex("APPROVE:0"));
        when(approvals.findForUpdate(projectId, runId, approvalId)).thenReturn(Optional.of(completed));
        when(runs.get(ownerId, projectId, runId)).thenReturn(run(AgentRun.Status.SUCCEEDED, 3));
        assertThat(service.decide(ownerId, projectId, runId, approvalId, 0,
                AgentMediaApprovalService.Decision.APPROVE, "approve-command").status())
                .isEqualTo(AgentMediaApproval.Status.SUCCEEDED);
        assertThatThrownBy(() -> service.decide(ownerId, projectId, runId, approvalId, 0,
                AgentMediaApprovalService.Decision.REJECT, "approve-command"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
        verifyNoInteractions(mediaTasks);
        verify(approvals, never()).update(any(), anyLong());
    }

    @Test
    void oldBatchCannotBeApprovedAfterRunAdvances() {
        when(approvals.findForUpdate(projectId, runId, approvalId))
                .thenReturn(Optional.of(pending(NOW.plusSeconds(60), 1)));
        when(runs.get(ownerId, projectId, runId)).thenReturn(run(AgentRun.Status.RUNNING, 3));
        assertThatThrownBy(() -> service.decide(ownerId, projectId, runId, approvalId, 0,
                AgentMediaApprovalService.Decision.APPROVE, "approve-command"))
                .isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(mediaTasks);
    }

    @Test
    void lastModelTurnCannotProposeABatchWithNoResumeCapacity() {
        AgentRun run = run(AgentRun.Status.RUNNING, AgentRun.MAX_MODEL_TURNS - 1);
        assertThatThrownBy(() -> service.propose(new TrustedToolContext(ownerId, projectId, runId),
                run, UUID.randomUUID(), AgentRun.MAX_MODEL_TURNS - 1, "call-media", "{}"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("TOOL_ARGUMENT_INVALID"));
        verifyNoInteractions(approvals, mediaTasks, artifacts);
    }

    @Test
    void referenceOutsideExactRunScopeIsRejectedBeforeCreatingDrafts() {
        AgentRun run = run(AgentRun.Status.RUNNING, 1);
        UUID referenceId = UUID.randomUUID();
        when(runs.get(ownerId, projectId, runId)).thenReturn(run);
        when(approvals.findByToolCall(projectId, runId, 1, "call-media"))
                .thenReturn(Optional.empty());
        when(artifacts.requireAgentVisibleVersion(ownerId, projectId, runId, referenceId,
                run.contextSnapshot())).thenThrow(new ApiProblemException(HttpStatus.FORBIDDEN,
                        "INPUT_SCOPE_DENIED", ApiMessage.of("api.agent-media-approval.invalid-title"),
                        ApiMessage.of("api.agent-media-approval.invalid-detail", "reference"), false));

        String arguments = "{\"outputs\":[{\"kind\":\"IMAGE\",\"title\":\"Image\",\"prompt\":\"Draw\","
                + "\"mediaInputs\":[{\"versionId\":\"" + referenceId + "\",\"role\":\"REFERENCE\"}]}]}";
        assertThatThrownBy(() -> service.propose(new TrustedToolContext(ownerId, projectId, runId),
                run, UUID.randomUUID(), 1, "call-media", arguments))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("INPUT_SCOPE_DENIED"));
        verify(artifacts, never()).create(any(), any(), any(), any(), any());
        verifyNoInteractions(mediaTasks, drafts, canvas);
    }

    @Test
    void readingApprovalsRequiresRunAuthorizationBeforeAccessingLedger() {
        when(runs.get(ownerId, projectId, runId)).thenThrow(new ApiProblemException(
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                ApiMessage.of("api.agent-media-approval.not-found-title"),
                ApiMessage.of("api.agent-media-approval.not-found-detail"), false));
        assertThatThrownBy(() -> service.list(ownerId, projectId, runId))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.get(ownerId, projectId, runId, approvalId))
                .isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(approvals);
    }

    private AgentRun run(AgentRun.Status status, int nextStep) {
        return new AgentRun(runId, projectId, UUID.randomUUID(), UUID.randomUUID(), 1,
                ownerId, status, "Generate media", mapper.createObjectNode(), mapper.createObjectNode(),
                1, nextStep, 0, NOW, NOW, status.terminal() ? NOW : null);
    }

    private AgentMediaApproval pending(Instant expiresAt, int count) {
        ObjectNode request = mapper.createObjectNode().put("schemaVersion", 1);
        var outputs = request.putArray("outputs");
        ObjectNode targets = mapper.createObjectNode().put("schemaVersion", 1);
        var targetOutputs = targets.putArray("outputs");
        for (int index = 0; index < count; index++) {
            outputs.addObject().put("kind", "IMAGE").put("title", "Image");
            ObjectNode target = targetOutputs.addObject();
            target.put("artifactId", (index == 0 ? artifactId : UUID.randomUUID()).toString());
            target.put("canvasItemId", (index == 0 ? canvasItemId : UUID.randomUUID()).toString());
            target.put("draftVersion", 1);
            target.put("frozenInputHash", "frozen-hash");
            target.set("binding", mapper.valueToTree(binding));
            target.set("preview", mapper.createObjectNode().put("priceUnknown", true));
        }
        return new AgentMediaApproval(approvalId, ownerId, projectId, runId, 1, "call-media",
                UUID.randomUUID(), request, targets, List.of(), null, AgentMediaApproval.Status.PENDING,
                0, NOW.minusSeconds(60), expiresAt, null, null, null);
    }

    private DirectMediaTaskService.MediaPreflight preflight(String hash) {
        return new DirectMediaTaskService.MediaPreflight(Task.Kind.IMAGE_GENERATION, binding,
                hash, 1, mapper.createObjectNode().put("priceUnknown", true));
    }

    private JsonNodeIds ids(tools.jackson.databind.JsonNode target) {
        return new JsonNodeIds(UUID.fromString(target.path("artifactId").asText()),
                UUID.fromString(target.path("canvasItemId").asText()));
    }

    private record JsonNodeIds(UUID artifactId, UUID itemId) {}
}
