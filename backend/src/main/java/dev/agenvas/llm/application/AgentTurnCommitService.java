package dev.agenvas.llm.application;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.AgentTurnLeaseGuard;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Commits one model-turn scheduling decision with its fenced Task outcome. */
@Service
public class AgentTurnCommitService {

    private final TaskRepository taskRepository;
    private final TaskService tasks;
    private final AgentRunService runs;
    private final AgentTurnLeaseGuard leaseGuard;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final LlmTurnRepository turns;
    private final ToolExecutionRepository toolExecutions;
    private final LlmProtocolCodec codec;

    public AgentTurnCommitService(TaskRepository taskRepository, TaskService tasks,
            AgentRunService runs, AgentTurnLeaseGuard leaseGuard,
            ProjectEventService events, ObjectMapper mapper,
            LlmTurnRepository turns, ToolExecutionRepository toolExecutions,
            LlmProtocolCodec codec) {
        this.taskRepository = taskRepository;
        this.tasks = tasks;
        this.runs = runs;
        this.leaseGuard = leaseGuard;
        this.events = events;
        this.mapper = mapper;
        this.turns = turns;
        this.toolExecutions = toolExecutions;
        this.codec = codec;
    }

    /** Moves QUEUED to RUNNING only while this Task's lease remains current. */
    @Transactional
    public AgentRun start(Task lease, String workerId) {
        UUID ownerId = ownerId(lease);
        return events.recordChange(ownerId, lease.projectId(), () -> {
            leaseGuard.requireActive(lease, workerId);
            AgentRun current = runs.get(ownerId, lease.projectId(), lease.runId());
            requireStep(lease, current);
            if (current.status() == AgentRun.Status.QUEUED) {
                current = runs.transition(ownerId, lease.projectId(), lease.runId(),
                        current.version(), AgentRun.Status.RUNNING);
            } else if (current.status() == AgentRun.Status.WAITING_TASKS
                    && current.nextStepIndex() == lease.input().path("stepIndex").asInt(-1)) {
                current = runs.transition(ownerId, lease.projectId(), lease.runId(),
                        current.version(), AgentRun.Status.RUNNING);
            } else if (current.status() != AgentRun.Status.RUNNING
                    && current.status() != AgentRun.Status.WAITING_APPROVAL
                    && current.status() != AgentRun.Status.WAITING_TASKS) {
                throw new IllegalStateException("Run cannot resume this model turn");
            }
            return ProjectEventService.Change.unchanged(current);
        }).value();
    }

    /** Finishes and optionally schedules the next model turn in one database transaction. */
    @Transactional
    public Decision complete(Task lease, String workerId) {
        UUID ownerId = ownerId(lease);
        return events.recordChange(ownerId, lease.projectId(), () -> {
            leaseGuard.requireActive(lease, workerId);
            AgentRun run = runs.get(ownerId, lease.projectId(), lease.runId());
            int stepIndex = requireStep(lease, run);
            LlmTurn turn = turns.find(lease.projectId(), lease.runId(), stepIndex)
                    .orElseThrow(() -> new IllegalStateException("Model turn checkpoint is missing"));
            if (turn.status() != LlmTurn.Status.RESPONDED) {
                throw new IllegalStateException("Model response is not durable yet");
            }
            AssistantMessage assistant = codec.selectedAssistant(turn.response());
            Decision decision = decisionFor(lease, stepIndex, assistant);
            ObjectNode output = mapper.createObjectNode();
            output.put("decision", decision.name());
            output.put("stepIndex", stepIndex);
            output.put("assistantText", assistant.getText() == null ? "" : assistant.getText());
            if (decision == Decision.CONTINUE) {
                if (run.status() != AgentRun.Status.RUNNING) {
                    throw new IllegalStateException("Run state prevents model continuation");
                }
                if (stepIndex >= 11) {
                    // The response and tool ledger are durable; stop without scheduling turn 13.
                    tasks.fail(lease, workerId, "MODEL_TURN_LIMIT");
                    runs.transition(ownerId, lease.projectId(), lease.runId(),
                            run.version(), AgentRun.Status.BLOCKED);
                    return ProjectEventService.Change.unchanged(Decision.LIMIT_REACHED);
                }
                ObjectNode input = mapper.createObjectNode();
                input.put("schemaVersion", 1);
                input.put("stepIndex", stepIndex + 1);
                tasks.create(ownerId, lease.projectId(), lease.runId(), null,
                        "agent-turn-" + (stepIndex + 1), Task.Kind.AGENT_TURN, input,
                        null, 1, List.of(lease.id()));
                tasks.succeed(lease, workerId, output);
                runs.advanceStep(ownerId, lease.projectId(), lease.runId(),
                        run.version(), stepIndex);
            } else if (decision == Decision.WAIT_APPROVAL) {
                if (run.status() != AgentRun.Status.WAITING_APPROVAL
                        && run.status() != AgentRun.Status.WAITING_TASKS
                        && !(run.status() == AgentRun.Status.RUNNING
                                && run.nextStepIndex() == stepIndex + 1)) {
                    throw new IllegalStateException("Run is not waiting for an approved plan");
                }
                tasks.succeed(lease, workerId, output);
            } else if (decision == Decision.FINISH) {
                if (run.status() != AgentRun.Status.RUNNING) {
                    throw new IllegalStateException("Only a running Run can finish");
                }
                tasks.succeed(lease, workerId, output);
                runs.transition(ownerId, lease.projectId(), lease.runId(),
                        run.version(), AgentRun.Status.SUCCEEDED);
            } else {
                throw new IllegalArgumentException("Unknown model turn decision");
            }
            return ProjectEventService.Change.unchanged(decision);
        }).value();
    }

    /** Schedules at most two counted model repairs after a fully rolled-back invalid response. */
    @Transactional
    public boolean scheduleRepair(Task lease, String workerId, String errorCode,
            String errorDetail) {
        UUID ownerId = ownerId(lease);
        return events.recordChange(ownerId, lease.projectId(), () -> {
            leaseGuard.requireActive(lease, workerId);
            AgentRun run = runs.get(ownerId, lease.projectId(), lease.runId());
            int stepIndex = requireStep(lease, run);
            LlmTurn turn = turns.find(lease.projectId(), lease.runId(), stepIndex)
                    .orElseThrow(() -> new IllegalStateException("Invalid model turn is missing"));
            if (run.status() != AgentRun.Status.RUNNING
                    || turn.status() != LlmTurn.Status.RESPONDED) {
                throw new IllegalStateException("Run cannot repair this model response");
            }
            int attempt = lease.input().path("repairAttempt").asInt(0);
            if (attempt < 0 || attempt > 2) {
                throw new IllegalStateException("Invalid model repair count");
            }
            if (attempt >= 2 || stepIndex >= 11) {
                tasks.fail(lease, workerId, "MODEL_OUTPUT_INVALID");
                runs.transition(ownerId, lease.projectId(), lease.runId(),
                        run.version(), AgentRun.Status.BLOCKED);
                return ProjectEventService.Change.unchanged(false);
            }
            ObjectNode input = mapper.createObjectNode();
            input.put("schemaVersion", 1);
            input.put("stepIndex", stepIndex + 1);
            input.put("repairAttempt", attempt + 1);
            input.put("repairFromStep", stepIndex);
            input.put("repairErrorCode", errorCode);
            input.put("repairErrorDetail", errorDetail.substring(0,
                    Math.min(errorDetail.length(), 300)));
            tasks.create(ownerId, lease.projectId(), lease.runId(), null,
                    "agent-turn-" + (stepIndex + 1), Task.Kind.AGENT_TURN, input,
                    null, 1, List.of(lease.id()));
            ObjectNode output = mapper.createObjectNode();
            output.put("decision", "REPAIR");
            output.put("stepIndex", stepIndex);
            tasks.succeed(lease, workerId, output);
            runs.advanceStep(ownerId, lease.projectId(), lease.runId(),
                    run.version(), stepIndex);
            return ProjectEventService.Change.unchanged(true);
        }).value();
    }

    /** Blocks a confirmed local failure without discarding earlier committed tool results. */
    @Transactional
    public void block(Task lease, String workerId, String errorCode) {
        UUID ownerId = ownerId(lease);
        events.recordChange(ownerId, lease.projectId(), () -> {
            leaseGuard.requireActive(lease, workerId);
            AgentRun run = runs.get(ownerId, lease.projectId(), lease.runId());
            requireStep(lease, run);
            if (run.status() != AgentRun.Status.RUNNING
                    && run.status() != AgentRun.Status.QUEUED) {
                throw new IllegalStateException("Run state changed before failure handling");
            }
            tasks.fail(lease, workerId, errorCode);
            runs.transition(ownerId, lease.projectId(), lease.runId(),
                    run.version(), AgentRun.Status.BLOCKED);
            return ProjectEventService.Change.unchanged(null);
        });
    }

    private UUID ownerId(Task lease) {
        if (lease == null || lease.kind() != Task.Kind.AGENT_TURN) {
            throw new IllegalArgumentException("Expected a model-turn Task");
        }
        return taskRepository.ownerId(lease.id())
                .orElseThrow(() -> new IllegalStateException("Model Task owner is missing"));
    }

    private int requireStep(Task lease, AgentRun run) {
        int stepIndex = lease.input().path("stepIndex").asInt(-1);
        if (lease.input().path("schemaVersion").asInt(-1) != 1
                || stepIndex < 0 || stepIndex >= 12
                || (run.nextStepIndex() != stepIndex
                        && !(run.nextStepIndex() == stepIndex + 1
                                && (run.status() == AgentRun.Status.WAITING_TASKS
                                        || run.status() == AgentRun.Status.RUNNING)))) {
            throw new IllegalStateException("Model Task does not match Run step cursor");
        }
        return stepIndex;
    }

    private Decision decisionFor(Task lease, int stepIndex, AssistantMessage assistant) {
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
        if (calls.isEmpty()) {
            return Decision.FINISH;
        }
        boolean waitsForApproval = false;
        for (int index = 0; index < calls.size(); index++) {
            AssistantMessage.ToolCall call = calls.get(index);
            ToolExecution execution = toolExecutions.find(lease.projectId(), lease.runId(),
                    stepIndex, call.id()).orElseThrow(() ->
                            new IllegalStateException("Model tool result is missing"));
            if (execution.status() != ToolExecution.Status.COMPLETED
                    || execution.result() == null
                    || !execution.toolName().equals(call.name())) {
                throw new IllegalStateException("Model tool result does not match response");
            }
            if ("propose_generation_plan".equals(call.name())) {
                if (index != calls.size() - 1
                        || !"WAITING_APPROVAL".equals(
                                execution.result().path("status").asText())) {
                    throw new IllegalStateException("Plan proposal must be the last tool call");
                }
                waitsForApproval = true;
            } else if ("WAITING_APPROVAL".equals(
                    execution.result().path("status").asText())) {
                throw new IllegalStateException("Unexpected approval wait from another tool");
            }
        }
        return waitsForApproval ? Decision.WAIT_APPROVAL : Decision.CONTINUE;
    }

    /** A completed assistant message, an approval wait, or a durable next step. */
    public enum Decision { CONTINUE, WAIT_APPROVAL, FINISH, LIMIT_REACHED }
}
