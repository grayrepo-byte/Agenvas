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

/** 在同一项目变更事务中提交模型回合的 Run 状态、任务结果和后续调度决定。 */
@Service
public class AgentTurnCommitService {

    /** 反查任务所有者，并核验回合任务的持久化状态。 */
    private final TaskRepository taskRepository;
    /** 带租约 fencing 的任务完成、失败与下一回合创建入口。 */
    private final TaskService tasks;
    /** 使用 Run 的预期版本推进状态和步骤游标。 */
    private final AgentRunService runs;
    /** 在每个写事务内确认 Worker 仍持有当前租约。 */
    private final AgentTurnLeaseGuard leaseGuard;
    /** 将状态变化与项目事件提交在同一事务。 */
    private final ProjectEventService events;
    /** 构造任务输出和修复回合的固定 JSON 输入。 */
    private final ObjectMapper mapper;
    /** 确认模型响应已完整持久化，才允许完成回合。 */
    private final LlmTurnRepository turns;
    /** 核对响应中的每个工具调用都有对应的已完成账本行。 */
    private final ToolExecutionRepository toolExecutions;
    /** 从保存的响应恢复被选中的 Assistant 消息及调用顺序。 */
    private final LlmProtocolCodec codec;

    /** 组装回合租约校验、Run 推进、事件提交和完整模型响应核验能力。
     * @param taskRepository 读取任务项目所有者
     * @param tasks 条件完成回合任务并创建后续任务
     * @param runs 推进 Run 状态和模型步骤游标
     * @param leaseGuard 在副作用事务内重新核验租约
     * @param events 与状态变化同事务追加项目事件
     * @param mapper 构造任务输出和事件 JSON
     * @param turns 核验完整响应检查点
     * @param toolExecutions 核验模型响应内工具账本
     * @param codec 从检查点恢复原始调用顺序
     */
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

    /**
     * 当前租约有效且步骤游标匹配时启动或恢复 Run；等待态重放保留已保存响应的状态。
     *
     * @param lease 当前 AGENT_TURN 任务的租约快照
     * @param workerId 租约持有者，必须与任务行匹配
     * @return 事务提交后可用于本轮处理的 Run 状态
     */
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

    /**
     * 先确认响应和全部工具结果已落库，再按 FINISH、等待审批或继续回合原子推进任务与 Run。
     * 达到回合上限时阻断 Run，且不再创建新的模型任务。
     *
     * @param lease 要完成的模型回合任务租约
     * @param workerId 当前租约持有者
     * @return 本轮持久化的调度决定
     */
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
                    // 当前响应及工具账本已持久化；达到上限后停止，不创建第 13 轮任务。
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

    /**
     * 工具批次因参数或计划错误回滚后，最多创建两个修复回合；超限时阻断 Run。
     * 原始无效响应仍留在检查点，供修复上下文引用和问题核查。
     *
     * @param lease 产生无效响应的当前任务租约
     * @param workerId 当前租约持有者
     * @param errorCode 允许模型修复的稳定错误码
     * @param errorDetail 截断到 300 字符后保存的安全错误说明
     * @return 已创建修复回合时为 true，达到上限并阻断时为 false
     */
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

    /**
     * 仅在仍有效的租约与可执行 Run 状态下记录本地失败并阻断运行；先前已提交的工具结果保留。
     *
     * @param lease 发生失败的当前模型任务租约
     * @param workerId 当前租约持有者
     * @param errorCode 可向任务历史展示的稳定错误码
     */
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

    /**
     * 验证任务确属模型回合，并从数据库读取所有者，避免接受工具或模型自报身份。
     *
     * @param lease 当前任务租约
     * @return 数据库记录的项目所有者 ID
     */
    private UUID ownerId(Task lease) {
        if (lease == null || lease.kind() != Task.Kind.AGENT_TURN) {
            throw new IllegalArgumentException("Expected a model-turn Task");
        }
        return taskRepository.ownerId(lease.id())
                .orElseThrow(() -> new IllegalStateException("Model Task owner is missing"));
    }

    /**
     * 校验任务 Schema 与 Run 步骤游标。允许当前步骤，也允许等待恢复时已前移一格的游标。
     *
     * @param lease 带固定 stepIndex 的任务输入
     * @param run 当前持久化的 Run 状态和步骤游标
     * @return 本任务对应的有效步骤序号
     */
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

    /**
     * 逐项比对模型调用与已完成工具账本；无工具时结束，末尾计划提案进入审批，其余情况继续下一回合。
     *
     * @param lease 当前任务，用于限定项目和 Run
     * @param stepIndex 模型响应与工具账本共同使用的步骤序号
     * @param assistant 已保存响应中被选中的 Assistant 消息
     * @return 本轮允许提交的调度决定
     */
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
                        || !ToolResultStatus.WAITING_APPROVAL.name().equals(
                                execution.result().path("status").asText())) {
                    throw new IllegalStateException("Plan proposal must be the last tool call");
                }
                waitsForApproval = true;
            } else if (ToolResultStatus.WAITING_APPROVAL.name().equals(
                    execution.result().path("status").asText())) {
                throw new IllegalStateException("Unexpected approval wait from another tool");
            }
        }
        return waitsForApproval ? Decision.WAIT_APPROVAL : Decision.CONTINUE;
    }

    /** 模型回合完成后可以持久化的四种调度结果。 */
    public enum Decision {
        /** 工具执行完成，继续创建下一个模型回合。 */
        CONTINUE,
        /** 媒体计划已提出，等待用户审批。 */
        WAIT_APPROVAL,
        /** 无后续工具调用，Run 可以结束。 */
        FINISH,
        /** 已达到模型回合上限，Run 被阻断。 */
        LIMIT_REACHED
    }
}
