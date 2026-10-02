package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.event.application.ProjectEventRecorded;
import dev.agenvas.event.domain.ProjectEvent;
import dev.agenvas.llm.domain.AgentMediaApproval;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

/** Failed/unknown/expired generation must produce an Agent reply without resubmitting or exposing receipts. */
class AgentMediaOutcomeServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final AgentMediaApprovalRepository approvals = mock(AgentMediaApprovalRepository.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final JsonMapper mapper = new JsonMapper();
    private final AgentMediaOutcomeService service = new AgentMediaOutcomeService(approvals, runs,
            tasks, events, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @EnumSource(value = Task.Status.class, names = {"SUCCEEDED", "FAILED", "CANCELED", "BLOCKED", "UNKNOWN"})
    @SuppressWarnings("unchecked")
    void everyResolvedOutcomeCreatesAContinuationWithoutASuccessOnlyDependency(Task.Status status) {
        AgentMediaApproval approval = approval(AgentMediaApproval.Status.APPROVED, NOW.plusSeconds(60));
        AgentRun run = mock(AgentRun.class);
        when(run.status()).thenReturn(AgentRun.Status.WAITING_TASKS);
        when(run.nextStepIndex()).thenReturn(1);
        when(run.version()).thenReturn(3L);
        when(runs.get(owner, project, runId)).thenReturn(run);
        when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(invocation -> {
            var change = ((Supplier<ProjectEventService.Change<Object>>) invocation.getArgument(2)).get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
        when(approvals.findForUpdate(project, runId, approvalId)).thenReturn(Optional.of(approval));
        when(approvals.update(any(), eq(approval.version()))).thenReturn(true);
        when(approvals.listByRun(project, runId)).thenReturn(List.of());
        Task task = mock(Task.class);
        when(task.id()).thenReturn(taskId);
        when(task.kind()).thenReturn(Task.Kind.IMAGE_GENERATION);
        when(task.status()).thenReturn(status);
        when(task.output()).thenReturn(mapper.createObjectNode().put("artifactVersionId", UUID.randomUUID().toString())
                .put("providerDownloadUrl", "PRIVATE_RECEIPT_MUST_NOT_ENTER_PROMPT")
                .put("providerUsage", "PRIVATE_BILLING"));
        when(tasks.get(owner, project, taskId)).thenReturn(task);

        service.reconcile(owner, project, runId, approvalId);

        var saved = org.mockito.ArgumentCaptor.forClass(AgentMediaApproval.class);
        verify(approvals).update(saved.capture(), eq(0L));
        assertThat(saved.getValue().status()).isEqualTo(status == Task.Status.SUCCEEDED
                ? AgentMediaApproval.Status.SUCCEEDED : AgentMediaApproval.Status.FAILED);
        assertThat(saved.getValue().result().toString()).doesNotContain("PRIVATE_RECEIPT", "PRIVATE_BILLING");
        verify(tasks).create(eq(owner), eq(project), eq(runId), eq("agent-turn-1"),
                eq(Task.Kind.AGENT_TURN), any(), eq(null), eq(1), eq(List.of()));
        verify(runs).transition(owner, project, runId, 3L, AgentRun.Status.RUNNING);
        verify(approvals).markNotified(project, runId, 0);
    }

    @Test
    void toolReplyIsUnavailableUntilFinalAndKeepsTheOriginalReceiptImmutable() {
        var receipt = mapper.createObjectNode().put("approvalId", approvalId.toString()).put("awaitingMedia", true);
        when(approvals.findByToolCall(project, runId, 0, "call-1"))
                .thenReturn(Optional.of(approval(AgentMediaApproval.Status.APPROVED, NOW.plusSeconds(60))));
        assertThatThrownBy(() -> service.finalToolResult(project, runId, 0, "call-1", receipt))
                .isInstanceOf(IllegalStateException.class);
        var completed = approval(AgentMediaApproval.Status.APPROVED, NOW.plusSeconds(60))
                .transition(AgentMediaApproval.Status.REJECTED, List.of(),
                        mapper.createObjectNode().put("schemaVersion", 1).put("status", "REJECTED"), null, "key", "hash");
        when(approvals.findByToolCall(project, runId, 0, "call-1")).thenReturn(Optional.of(completed));
        var reply = service.finalToolResult(project, runId, 0, "call-1", receipt);
        assertThat(reply.path("awaitingMedia").asBoolean()).isFalse();
        assertThat(reply.at("/mediaApproval/status").asText()).isEqualTo("REJECTED");
        assertThat(receipt.path("awaitingMedia").asBoolean()).isTrue();
        assertThat(receipt.has("mediaApproval")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executionDeadlineStopsUnresolvedLocalWorkAndSchedulesAnExpiryReply() {
        var approval = approval(AgentMediaApproval.Status.APPROVED, NOW.minusSeconds(1));
        AgentRun run = mock(AgentRun.class);
        when(run.status()).thenReturn(AgentRun.Status.WAITING_TASKS);
        when(run.nextStepIndex()).thenReturn(1);
        when(runs.get(owner, project, runId)).thenReturn(run);
        when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(invocation -> {
            var change = ((Supplier<ProjectEventService.Change<Object>>) invocation.getArgument(2)).get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
        when(approvals.findForUpdate(project, runId, approvalId)).thenReturn(Optional.of(approval));
        when(approvals.update(any(), anyLong())).thenReturn(true);
        when(approvals.listByRun(project, runId)).thenReturn(List.of());
        Task task = mock(Task.class);
        when(task.id()).thenReturn(taskId);
        when(task.kind()).thenReturn(Task.Kind.VIDEO_GENERATION);
        when(task.status()).thenReturn(Task.Status.WAITING_PROVIDER);
        when(task.providerRequestId()).thenReturn("original-provider-request");
        when(tasks.get(owner, project, taskId)).thenReturn(task);

        service.reconcile(owner, project, runId, approvalId);

        var saved = org.mockito.ArgumentCaptor.forClass(AgentMediaApproval.class);
        verify(approvals).update(saved.capture(), eq(0L));
        assertThat(saved.getValue().status()).isEqualTo(AgentMediaApproval.Status.EXPIRED);
        assertThat(saved.getValue().result().path("errorCode").asText()).isEqualTo("MEDIA_EXECUTION_EXPIRED");
        verify(tasks).cancelApprovedMedia(owner, project, taskId);
        verify(tasks).create(eq(owner), eq(project), eq(runId), eq("agent-turn-1"),
                eq(Task.Kind.AGENT_TURN), any(), eq(null), eq(1), eq(List.of()));
    }

    @ParameterizedTest
    @EnumSource(value = AgentRun.Status.class, names = {"CANCEL_REQUESTED", "CANCELED", "FAILED"})
    @SuppressWarnings("unchecked")
    void runStopEventFinalizesPendingApprovalWithoutContinuing(AgentRun.Status status) {
        var pending = approval(AgentMediaApproval.Status.PENDING, null);
        var approval = new AgentMediaApproval(pending.id(), owner, project, runId,
                pending.stepIndex(), pending.toolCallId(), pending.operationId(), pending.request(),
                pending.targets(), List.of(), null, pending.status(), pending.version(),
                pending.createdAt(), pending.expiresAt(), null, null, null);
        AgentRun run = mock(AgentRun.class);
        when(run.status()).thenReturn(status);
        when(runs.get(owner, project, runId)).thenReturn(run);
        when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(invocation -> {
            var change = ((Supplier<ProjectEventService.Change<Object>>) invocation.getArgument(2)).get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
        when(approvals.listByRun(project, runId)).thenReturn(List.of(approval));
        when(approvals.findForUpdate(project, runId, approvalId)).thenReturn(Optional.of(approval));
        when(approvals.update(any(), eq(0L))).thenReturn(true);

        service.onProjectEvent(new ProjectEventRecorded(owner, new ProjectEvent(project, 1,
                UUID.randomUUID(), "agent.run.changed", 1, runId, 1,
                mapper.createObjectNode().put("status", status.name()), NOW)));

        var saved = org.mockito.ArgumentCaptor.forClass(AgentMediaApproval.class);
        verify(approvals).update(saved.capture(), eq(0L));
        assertThat(saved.getValue().status()).isEqualTo(AgentMediaApproval.Status.CANCELED);
        verify(tasks, never()).create(any(), any(), any(), any(), any(), any(), any(), any(int.class), any());
    }

    private AgentMediaApproval approval(AgentMediaApproval.Status status, Instant deadline) {
        var request = mapper.createObjectNode().put("schemaVersion", 1);
        request.putArray("outputs").addObject().put("kind", "IMAGE");
        return new AgentMediaApproval(approvalId, owner, project, runId, 0, "call-1", UUID.randomUUID(),
                request, request, List.of(taskId), null, status, 0, NOW.minusSeconds(60),
                NOW.plusSeconds(60), deadline, null, null);
    }
}
