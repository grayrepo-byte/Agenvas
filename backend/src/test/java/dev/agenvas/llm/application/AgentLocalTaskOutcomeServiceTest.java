package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.event.application.ProjectEventService;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Terminal local replies are frozen once and resume the original tool round, including failures. */
class AgentLocalTaskOutcomeServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), runId = UUID.randomUUID();
    private final UUID operation = UUID.randomUUID(), taskId = UUID.randomUUID(), artifact = UUID.randomUUID();
    private final UUID version = UUID.randomUUID();
    private final JsonMapper mapper = new JsonMapper();
    private final AgentMediaApprovalRepository approvals = mock(AgentMediaApprovalRepository.class);
    private final ToolExecutionRepository ledger = mock(ToolExecutionRepository.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final Task task = mock(Task.class);
    private final AgentRun run = mock(AgentRun.class);
    private final AtomicReference<AgentRun.Status> runStatus = new AtomicReference<>(AgentRun.Status.WAITING_TASKS);
    private final AtomicReference<ToolExecution> saved = new AtomicReference<>();
    private final AgentMediaOutcomeService service = new AgentMediaOutcomeService(approvals, runs, tasks,
            events, mapper, Clock.fixed(NOW, ZoneOffset.UTC), ledger);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        ObjectNode receipt = mapper.createObjectNode().put("status", "SUCCEEDED").put("awaitingLocalTask", true);
        receipt.putArray("taskIds").add(taskId.toString());
        receipt.putObject("data").put("taskId", taskId.toString()).put("queued", true).put("completed", false);
        saved.set(execution(receipt));
        when(ledger.localTaskExecutions(project, runId)).thenAnswer(ignored -> List.of(saved.get()));
        when(ledger.find(project, runId, 0, "call-local")).thenAnswer(ignored -> Optional.of(saved.get()));
        when(ledger.appendLocalTaskOutcome(eq(operation), any())).thenAnswer(invocation -> {
            ObjectNode result = (ObjectNode) saved.get().result().deepCopy();
            result.set("localTaskOutcome", invocation.getArgument(1, JsonNode.class));
            saved.set(execution(result));
            return true;
        });
        when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(invocation -> {
            var change = ((Supplier<ProjectEventService.Change<Object>>) invocation.getArgument(2)).get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
        when(run.status()).thenAnswer(ignored -> runStatus.get());
        when(run.nextStepIndex()).thenReturn(1);
        when(run.version()).thenReturn(3L);
        when(runs.get(owner, project, runId)).thenReturn(run);
        when(runs.transition(owner, project, runId, 3L, AgentRun.Status.RUNNING)).thenAnswer(ignored -> {
            runStatus.set(AgentRun.Status.RUNNING); return run;
        });
        when(tasks.get(owner, project, taskId)).thenReturn(task);
        when(task.id()).thenReturn(taskId);
        when(task.kind()).thenReturn(Task.Kind.AUDIO_GENERATION);
        when(task.agentLocalRunId()).thenReturn(runId);
        when(task.agentLocalOperationId()).thenReturn(operation);
        when(task.input()).thenReturn(mapper.createObjectNode().put("artifactId", artifact.toString()));
        when(task.output()).thenReturn(mapper.createObjectNode().put("artifactId", artifact.toString())
                .put("artifactVersionId", version.toString()).put("selected", true)
                .put("providerDownloadUrl", "PRIVATE_RECEIPT").put("providerUsage", "PRIVATE_BILLING"));
    }

    @ParameterizedTest
    @EnumSource(value = Task.Status.class, names = {"SUCCEEDED", "FAILED", "CANCELED", "BLOCKED", "UNKNOWN"})
    void terminalLocalTaskResumesOnceAndProjectsOnlyArchivedFields(Task.Status status) {
        when(task.status()).thenReturn(status);
        assertThat(service.hasOutstanding(project, runId, 0)).isTrue();
        service.reconcileLocalTasks(owner, project, runId);
        service.reconcileLocalTasks(owner, project, runId);
        JsonNode result = service.finalToolResult(project, runId, 0, "call-local", saved.get().result());
        assertThat(result.path("awaitingLocalTask").asBoolean()).isFalse();
        assertThat(result.at("/data/status").asText()).isEqualTo(status.name());
        assertThat(result.at("/data/completed").asBoolean()).isEqualTo(status == Task.Status.SUCCEEDED);
        assertThat(result.at("/data/resolved").asBoolean()).isTrue();
        assertThat(result.at("/data/artifactVersionId").asText()).isEqualTo(version.toString());
        assertThat(result.toString()).doesNotContain("PRIVATE_RECEIPT", "PRIVATE_BILLING");
        assertThat(saved.get().result().path("awaitingLocalTask").asBoolean()).isTrue();
        assertThat(saved.get().result().at("/data/queued").asBoolean()).isTrue();
        assertThat(service.hasOutstanding(project, runId, 0)).isFalse();
        verify(ledger, times(1)).appendLocalTaskOutcome(eq(operation), any());
        verify(tasks, times(1)).create(eq(owner), eq(project), eq(runId), eq("agent-turn-1"),
                eq(Task.Kind.AGENT_TURN), any(), eq(1));
    }

    @Test void unknownLateSuccessCannotRewriteTheDeliveredReply() {
        when(task.status()).thenReturn(Task.Status.UNKNOWN);
        when(task.output()).thenReturn(null);
        service.reconcileLocalTasks(owner, project, runId);
        JsonNode original = service.finalToolResult(project, runId, 0, "call-local", saved.get().result());
        when(task.status()).thenReturn(Task.Status.SUCCEEDED);
        when(task.output()).thenReturn(mapper.createObjectNode().put("artifactId", artifact.toString())
                .put("artifactVersionId", version.toString()));
        service.reconcileLocalTasks(owner, project, runId);
        assertThat(service.finalToolResult(project, runId, 0, "call-local", saved.get().result())).isEqualTo(original);
        assertThat(original.at("/data/artifactVersionId").isMissingNode()).isTrue();
        verify(ledger, times(1)).appendLocalTaskOutcome(eq(operation), any());
    }

    @ParameterizedTest
    @EnumSource(value = AgentRun.Status.class, names = {"CANCELED", "FAILED", "SUCCEEDED", "CANCEL_REQUESTED"})
    void stoppedRunStillRecordsOutcomeWithoutContinuingOrCancelingAcceptedLocalTask(AgentRun.Status status) {
        runStatus.set(status);
        when(task.status()).thenReturn(Task.Status.SUCCEEDED);
        service.reconcileLocalTasks(owner, project, runId);
        verify(ledger).appendLocalTaskOutcome(eq(operation), any());
        verify(tasks, never()).create(any(), any(), any(), any(), any(), any(), any(int.class));
        verify(tasks, never()).cancelApprovedMedia(any(), any(), any());
    }

    @Test void outstandingApprovalPreventsLocalCompletionFromResumingTheSameRound() {
        when(task.status()).thenReturn(Task.Status.SUCCEEDED);
        var approval = mock(AgentMediaApproval.class);
        when(approval.stepIndex()).thenReturn(0);
        when(approval.status()).thenReturn(AgentMediaApproval.Status.PENDING);
        when(approvals.listByRun(project, runId)).thenReturn(List.of(approval));
        service.reconcileLocalTasks(owner, project, runId);
        assertThat(service.hasOutstanding(project, runId, 0)).isTrue();
        verify(tasks, never()).create(any(), any(), any(), any(), any(), any(), any(int.class));
    }

    @Test void queuedReceiptCannotBePresentedAsCompleted() {
        when(task.status()).thenReturn(Task.Status.RUNNING);
        service.reconcileLocalTasks(owner, project, runId);
        assertThatThrownBy(() -> service.finalToolResult(project, runId, 0, "call-local", saved.get().result()))
                .isInstanceOf(IllegalStateException.class);
        verify(ledger, never()).appendLocalTaskOutcome(any(), any());
        verify(tasks, never()).create(any(), any(), any(), any(), any(), any(), any(int.class));
    }

    @Test void successfulTaskRequiresAnActualArchivedVersion() {
        when(task.status()).thenReturn(Task.Status.SUCCEEDED);
        when(task.output()).thenReturn(mapper.createObjectNode().put("artifactId", artifact.toString()));
        assertThatThrownBy(() -> service.reconcileLocalTasks(owner, project, runId)).isInstanceOf(IllegalStateException.class);
        verify(ledger, never()).appendLocalTaskOutcome(any(), any());
    }

    @Test void originMustMatchBothRunAndCommittedOperation() {
        when(task.agentLocalOperationId()).thenReturn(UUID.randomUUID());
        when(task.status()).thenReturn(Task.Status.SUCCEEDED);
        assertThatThrownBy(() -> service.reconcileLocalTasks(owner, project, runId)).isInstanceOf(IllegalStateException.class);
        verify(ledger, never()).appendLocalTaskOutcome(any(), any());
    }

    private ToolExecution execution(JsonNode receipt) {
        return new ToolExecution(operation, project, runId, 0, "call-local", "trim_audio", "fixed-hash",
                ToolExecution.Status.COMPLETED, receipt);
    }
}
