package dev.agenvas.llm.application;

import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Bounded, lease-fenced application loop for one persisted model turn at a time. */
@Service
public class AgentTurnWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentTurnWorker.class);
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(90);

    private final TaskService tasks;
    private final TaskRepository taskRepository;
    private final TaskProperties taskProperties;
    private final AgentTurnCommitService commits;
    private final InitialModelContextService initialContext;
    private final LlmConversationService conversation;
    private final PlanResumeContextService planResumeContext;
    private final RepairModelContextService repairContext;
    private final LlmRoundService rounds;
    private final LlmTurnRepository turns;
    private final LlmProtocolCodec codec;
    private final ToolBatchExecutionService executor;
    private final ToolRegistry registry;
    private final ChatGateway gateway;
    private final ScheduledExecutorService heartbeatExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("agent-turn-heartbeat").factory());
    private final ExecutorService modelExecutor = new ThreadPoolExecutor(1, 1,
            0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon().name("agent-turn-model").factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public AgentTurnWorker(TaskService tasks, TaskRepository taskRepository,
            TaskProperties taskProperties, AgentTurnCommitService commits,
            InitialModelContextService initialContext, LlmConversationService conversation,
            PlanResumeContextService planResumeContext, RepairModelContextService repairContext,
            LlmRoundService rounds, LlmTurnRepository turns, LlmProtocolCodec codec,
            ToolBatchExecutionService executor, ToolRegistry registry, ChatGateway gateway) {
        this.tasks = tasks;
        this.taskRepository = taskRepository;
        this.taskProperties = taskProperties;
        this.commits = commits;
        this.initialContext = initialContext;
        this.conversation = conversation;
        this.planResumeContext = planResumeContext;
        this.repairContext = repairContext;
        this.rounds = rounds;
        this.turns = turns;
        this.codec = codec;
        this.executor = executor;
        this.registry = registry;
        this.gateway = gateway;
    }

    /** Claims at most one Task and never occupies a database transaction during model I/O. */
    public synchronized int runOnce(String workerId) {
        List<Task> claimed = tasks.claimAgentTurns(workerId, 1);
        for (Task lease : claimed) {
            runClaimed(lease, workerId);
        }
        return claimed.size();
    }

    private void runClaimed(Task lease, String workerId) {
        long intervalMillis = Math.max(1_000, taskProperties.leaseDuration().toMillis() / 3);
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                tasks.heartbeat(lease.id(), workerId, lease.leaseEpoch());
            } catch (RuntimeException lost) {
                LOGGER.warn("Agent-turn lease heartbeat stopped for task {}", lease.id());
                throw lost;
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        try {
            AgentRun run = commits.start(lease, workerId);
            ChatGateway.ConfigIdentity pinned = new ChatGateway.ConfigIdentity(
                    run.policySnapshot().path("modelConfigSource").asText(""),
                    run.policySnapshot().path("modelConfigVersion").asInt(-1));
            gateway.requireToolCalling(pinned);
            UUID ownerId = taskRepository.ownerId(lease.id()).orElseThrow();
            int stepIndex = lease.input().path("stepIndex").asInt(-1);
            JsonNode response;
            if (run.status() == AgentRun.Status.WAITING_APPROVAL
                    || run.status() == AgentRun.Status.WAITING_TASKS
                    || run.nextStepIndex() > stepIndex) {
                LlmTurn turn = turns.find(lease.projectId(), lease.runId(), stepIndex)
                        .orElseThrow(() -> new IllegalStateException("Waiting Run has no model turn"));
                if (turn.status() != LlmTurn.Status.RESPONDED) {
                    throw new IllegalStateException("Waiting Run has no recorded model response");
                }
                response = turn.response();
            } else {
                List<Message> messages = lease.input().has("repairFromStep")
                        ? repairContext.assemble(ownerId, lease)
                        : stepIndex == 0
                                ? initialContext.assemble(ownerId, lease.projectId(), lease.runId())
                                : conversation.afterToolRound(ownerId, lease.projectId(),
                                        lease.runId(), stepIndex - 1);
                if (lease.input().has("resumePlanId")) {
                    messages = planResumeContext.append(ownerId, lease.projectId(),
                            lease.runId(), lease, messages);
                }
                List<Message> boundedMessages = messages;
                Future<JsonNode> call = modelExecutor.submit(() -> rounds.call(ownerId,
                        lease.projectId(), lease.runId(), stepIndex, boundedMessages,
                        registry.modelDefinitions(run.contextSnapshot().has("redoShotArtifactId")),
                        Map.of("projectId", lease.projectId().toString(),
                                "runId", lease.runId().toString())));
                try {
                    response = call.get(MODEL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception failure) {
                    call.cancel(true);
                    throw failure;
                }
            }
            try {
                AssistantMessage assistant = codec.selectedAssistant(response);
                validateCalls(assistant);
                TrustedToolContext context = new TrustedToolContext(ownerId,
                        lease.projectId(), lease.runId());
                executor.executeLeased(context, stepIndex, assistant.getToolCalls(),
                        lease, workerId);
            } catch (ApiProblemException invalid) {
                if (!"TOOL_ARGUMENT_INVALID".equals(invalid.code())
                        && !"PLAN_INVALID".equals(invalid.code())) {
                    throw invalid;
                }
                commits.scheduleRepair(lease, workerId, invalid.code(), invalid.getMessage());
                return;
            } catch (IllegalArgumentException invalid) {
                commits.scheduleRepair(lease, workerId, "MODEL_OUTPUT_INVALID",
                        "Model response or tool call structure is malformed");
                return;
            }
            commits.complete(lease, workerId);
        } catch (Exception failure) {
            LOGGER.error("Agent-turn task {} could not continue: {}", lease.id(),
                    failure.getClass().getSimpleName());
            try {
                commits.block(lease, workerId, failureCode(failure));
            } catch (RuntimeException changed) {
                LOGGER.warn("Agent-turn task {} changed before failure could be recorded: {}",
                        lease.id(), changed.getClass().getSimpleName());
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    /** Preserves only safe, stable configuration failures through Future wrappers. */
    static String failureCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiProblemException problem
                    && ("CREDENTIAL_KEY_VERSION_MISSING".equals(problem.code())
                            || "LLM_CONFIG_UNAVAILABLE".equals(problem.code()))) {
                return problem.code();
            }
        }
        return "AGENT_TURN_FAILED";
    }

    private void validateCalls(AssistantMessage assistant) {
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
        if (calls.size() > 40) {
            throw new IllegalArgumentException("Model emitted too many tool calls");
        }
        for (int index = 0; index < calls.size(); index++) {
            AssistantMessage.ToolCall call = calls.get(index);
            if (!"function".equals(call.type()) || call.id() == null || call.id().isBlank()
                    || call.name() == null || call.arguments() == null) {
                throw new IllegalArgumentException("Model emitted a malformed tool call");
            }
            if ("propose_generation_plan".equals(call.name())
                    && index != calls.size() - 1) {
                throw new IllegalArgumentException("A plan proposal must be the last tool call");
            }
        }
    }

    /** Stops bounded background threads during graceful shutdown. */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
        modelExecutor.shutdownNow();
    }
}
