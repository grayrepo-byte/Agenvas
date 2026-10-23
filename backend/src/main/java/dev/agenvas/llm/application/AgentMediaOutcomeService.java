package dev.agenvas.llm.application;

import dev.agenvas.event.application.ProjectEventRecorded;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.domain.AgentMediaApproval;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.application.WaitingRunCandidate;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Persists terminal media replies and one continuation together with the originating task event. */
@Service
public class AgentMediaOutcomeService {
    private static final int RESULT_SCHEMA_VERSION = 1;
    private static final Set<Task.Status> RESOLVED = Set.of(Task.Status.SUCCEEDED,
            Task.Status.FAILED, Task.Status.CANCELED, Task.Status.BLOCKED, Task.Status.UNKNOWN);
    private final AgentMediaApprovalRepository approvals;
    private final ToolExecutionRepository toolExecutions;
    private final AgentRunService runs;
    private final TaskService tasks;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AgentMediaOutcomeService(AgentMediaApprovalRepository approvals, AgentRunService runs,
            TaskService tasks, ProjectEventService events, ObjectMapper mapper, Clock clock,
            ToolExecutionRepository toolExecutions) {
        this.approvals = approvals;
        this.toolExecutions = toolExecutions;
        this.runs = runs;
        this.tasks = tasks;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Local signal is an optimization; recovery rechecks the durable approval and Task state. */
    @EventListener
    @Transactional
    public void onProjectEvent(ProjectEventRecorded recorded) {
        var event = recorded.event();
        if ("task.status.changed".equals(event.type())) {
            approvals.findByTaskId(event.projectId(), event.aggregateId()).ifPresent(approval ->
                    reconcile(recorded.ownerId(), approval.projectId(), approval.runId(), approval.id()));
            Task task = tasks.get(recorded.ownerId(), event.projectId(), event.aggregateId());
            if (task.agentLocalRunId() != null)
                reconcileLocalTasks(recorded.ownerId(), event.projectId(), task.agentLocalRunId());
        } else if ("agent.run.changed".equals(event.type())
                && ("CANCELED".equals(event.payload().path("status").asText())
                    || "CANCEL_REQUESTED".equals(event.payload().path("status").asText())
                    || "FAILED".equals(event.payload().path("status").asText()))) {
            for (var approval : approvals.listByRun(event.projectId(), event.aggregateId())) {
                if (!approval.status().terminal()) {
                    reconcile(recorded.ownerId(), approval.projectId(), approval.runId(), approval.id());
                }
            }
        }
    }

    @EventListener
    @Transactional
    public void onApprovalChanged(AgentMediaApprovalChanged changed) {
        reconcile(changed.ownerId(), changed.projectId(), changed.runId(), changed.approvalId());
    }

    /** A whole tool round waits until every proposal in it has a final reply. */
    public boolean hasOutstanding(UUID projectId, UUID runId, int stepIndex) {
        return approvals.listByRun(projectId, runId).stream().anyMatch(approval ->
                approval.stepIndex() == stepIndex && !approval.status().terminal())
                || toolExecutions.localTaskExecutions(projectId, runId).stream().anyMatch(execution ->
                        execution.stepIndex() == stepIndex && !AgentLocalTaskOutcome.resolved(execution.result()));
    }

    /** Pending approvals can have no Tasks yet; they still prevent ending a blocked Run. */
    @Transactional(readOnly = true)
    public boolean hasOutstanding(UUID projectId, UUID runId) {
        return approvals.listByRun(projectId, runId).stream().anyMatch(approval -> !approval.status().terminal())
                || toolExecutions.localTaskExecutions(projectId, runId).stream()
                        .anyMatch(execution -> !AgentLocalTaskOutcome.resolved(execution.result()));
    }

    /** Preserve the original tool receipt, then append its immutable final approval/task outcome. */
    public JsonNode finalToolResult(UUID projectId, UUID runId, int stepIndex,
            String toolCallId, JsonNode receipt) {
        if (AgentLocalTaskOutcome.awaits(receipt)) {
            ToolExecution execution = toolExecutions.find(projectId, runId, stepIndex, toolCallId)
                    .orElseThrow(() -> new IllegalStateException("Local tool receipt is missing"));
            if (execution.status() != ToolExecution.Status.COMPLETED || !AgentLocalTaskOutcome.awaits(execution.result()))
                throw new IllegalStateException("Local tool receipt is not committed");
            return AgentLocalTaskOutcome.forModel(execution.result());
        }
        var approval = approvals.findByToolCall(projectId, runId, stepIndex, toolCallId).orElse(null);
        if (approval == null) return receipt;
        if (!approval.status().terminal() || approval.result() == null) {
            throw new IllegalStateException("Media result has not been committed; Run must wait");
        }
        ObjectNode result = (ObjectNode) AgentMediaToolResult.forModel(receipt).deepCopy();
        result.put("awaitingMedia", false);
        result.set("mediaApproval", approval.result());
        return result;
    }

    /** Snapshot candidates only; each row is subsequently rechecked under its project lock. */
    @Transactional(readOnly = true)
    public List<AgentMediaApproval> recoveryCandidates(UUID afterId, int limit) {
        return approvals.listOutstandingAfter(afterId, limit);
    }

    /** Waiting Runs include the crash window after freezing an outcome but before scheduling its continuation. */
    @Transactional(readOnly = true)
    public List<WaitingRunCandidate> localRecoveryCandidates(UUID afterId, int limit) {
        return runs.waitingRecoveryCandidates(afterId, limit);
    }

    /** Local processing remains independent after Run cancellation; only active Runs can continue. */
    @Transactional
    public void reconcileLocalTasks(UUID ownerId, UUID projectId, UUID runId) {
        events.recordChange(ownerId, projectId, () -> {
            AgentRun run = runs.get(ownerId, projectId, runId);
            for (ToolExecution execution : toolExecutions.localTaskExecutions(projectId, runId)) {
                if (AgentLocalTaskOutcome.resolved(execution.result())) continue;
                Task task = tasks.get(ownerId, projectId, AgentLocalTaskOutcome.taskId(execution.result()));
                // Both identities are server-created and must agree; JSON flags alone never grant ownership.
                if (task.runId() != null || !runId.equals(task.agentLocalRunId())
                        || !execution.id().equals(task.agentLocalOperationId()))
                    throw new IllegalStateException("Local tool receipt does not own its task");
                if (!RESOLVED.contains(task.status())) continue;
                if (!toolExecutions.appendLocalTaskOutcome(execution.id(), AgentLocalTaskOutcome.freeze(task, mapper)))
                    throw new IllegalStateException("Locked local tool outcome changed unexpectedly");
            }
            if (run.status() == AgentRun.Status.WAITING_TASKS && run.nextStepIndex() > 0)
                resumeIfReady(ownerId, projectId, runId, run.nextStepIndex() - 1);
            return ProjectEventService.Change.unchanged(null);
        });
    }

    @Transactional
    public void reconcile(UUID ownerId, UUID projectId, UUID runId, UUID approvalId) {
        events.recordChange(ownerId, projectId, () -> {
            AgentRun run = runs.get(ownerId, projectId, runId);
            AgentMediaApproval approval = approvals.findForUpdate(projectId, runId, approvalId)
                    .orElseThrow(() -> new IllegalStateException("Media approval disappeared"));
            if (!approval.ownerId().equals(ownerId)) {
                throw new IllegalStateException("Media approval owner does not match Run");
            }
            if (!approval.status().terminal()) {
                if (run.status().terminal() || run.status() == AgentRun.Status.CANCEL_REQUESTED) {
                    List<Task> media = approval.taskIds().stream()
                            .map(id -> tasks.get(ownerId, projectId, id)).toList();
                    approval = finish(approval, AgentMediaApproval.Status.CANCELED,
                            "AGENT_RUN_STOPPED", media);
                    stopUnresolved(approval);
                } else if (approval.status() == AgentMediaApproval.Status.PENDING
                        && !clock.instant().isBefore(approval.expiresAt())) {
                    approval = finish(approval, AgentMediaApproval.Status.EXPIRED,
                            "AGENT_MEDIA_APPROVAL_EXPIRED", List.of());
                } else if (approval.status() == AgentMediaApproval.Status.APPROVED) {
                    List<Task> media = approval.taskIds().stream()
                            .map(id -> tasks.get(ownerId, projectId, id)).toList();
                    if (media.stream().allMatch(task -> RESOLVED.contains(task.status()))) {
                        boolean succeeded = media.stream().allMatch(task -> task.status() == Task.Status.SUCCEEDED);
                        approval = finish(approval, succeeded ? AgentMediaApproval.Status.SUCCEEDED
                                : AgentMediaApproval.Status.FAILED, succeeded ? null : "MEDIA_BATCH_FAILED", media);
                    } else if (!clock.instant().isBefore(approval.executionDeadline())) {
                        approval = finish(approval, AgentMediaApproval.Status.EXPIRED,
                                "MEDIA_EXECUTION_EXPIRED", media);
                        stopUnresolved(approval);
                    }
                }
            }
            if (approval.status().terminal()) resumeIfReady(ownerId, projectId, runId, approval.stepIndex());
            return ProjectEventService.Change.unchanged(null);
        });
    }

    private AgentMediaApproval finish(AgentMediaApproval approval, AgentMediaApproval.Status status,
            String errorCode, List<Task> media) {
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", RESULT_SCHEMA_VERSION)
                .put("status", status.name());
        if (errorCode != null) result.put("errorCode", errorCode);
        var taskResults = result.putArray("tasks");
        for (Task task : media) {
            taskResults.add(AgentMediaResultProjection.task(mapper, approval, task));
        }
        AgentMediaApproval updated = approval.transition(status, approval.taskIds(), result,
                approval.executionDeadline(), approval.decisionKey(), approval.decisionHash());
        if (!approvals.update(updated, approval.version())) {
            throw new IllegalStateException("Locked media approval changed unexpectedly");
        }
        var payload = mapper.createObjectNode().put("approvalId", updated.id().toString())
                .put("runId", updated.runId().toString()).put("status", status.name());
        events.append(updated.ownerId(), updated.projectId(), new ProjectEventService.EventDraft(
                "agent.media.approval.changed", RESULT_SCHEMA_VERSION, updated.id(), updated.version(), payload));
        return updated;
    }

    private void stopUnresolved(AgentMediaApproval approval) {
        for (UUID taskId : approval.taskIds()) {
            Task task = tasks.get(approval.ownerId(), approval.projectId(), taskId);
            // Run cancellation can already have canceled an unsubmitted Task. Its approval
            // reservation still needs the idempotent release performed by this entry point.
            if (!RESOLVED.contains(task.status()) || task.status() == Task.Status.CANCELED) {
                tasks.cancelApprovedMedia(approval.ownerId(), approval.projectId(), taskId);
            }
        }
    }

    private void resumeIfReady(UUID ownerId, UUID projectId, UUID runId, int priorStepIndex) {
        AgentRun run = runs.get(ownerId, projectId, runId);
        if (run.status().terminal() || (run.status() == AgentRun.Status.RUNNING
                && run.nextStepIndex() > priorStepIndex
                && !hasOutstanding(projectId, runId, priorStepIndex))) {
            approvals.markNotified(projectId, runId, priorStepIndex);
            return;
        }
        if (run.status() != AgentRun.Status.WAITING_TASKS
                || run.nextStepIndex() != priorStepIndex + 1
                || hasOutstanding(projectId, runId, priorStepIndex)) return;
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", RESULT_SCHEMA_VERSION)
                .put("stepIndex", run.nextStepIndex());
        // READY with no success-only dependency: failed and expired media also need a reply.
        tasks.create(ownerId, projectId, runId, "agent-turn-" + run.nextStepIndex(),
                Task.Kind.AGENT_TURN, input, 1);
        runs.transition(ownerId, projectId, runId, run.version(), AgentRun.Status.RUNNING);
        approvals.markNotified(projectId, runId, priorStepIndex);
    }
}
