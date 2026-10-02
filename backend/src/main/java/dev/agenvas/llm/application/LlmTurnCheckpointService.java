package dev.agenvas.llm.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.usage.application.UsageService;
import dev.agenvas.task.application.AgentTurnLeaseGuard;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 在独立短事务中保存模型请求与完整响应；网络调用不属于这些事务。 */
@Service
public class LlmTurnCheckpointService {

    /** 检查 Run 状态及启动时固定的模型配置版本。 */
    private final AgentRunRepository runs;
    /** 按 Run 和步骤序号存取请求、响应检查点。 */
    private final LlmTurnRepository turns;
    /** 保证检查点变化与项目事件在同一事务提交。 */
    private final ProjectEventService events;
    /** 构造只包含 Run ID 和步骤序号的事件负载。 */
    private final ObjectMapper mapper;
    /** 给请求预留和响应完成提供统一时间。 */
    private final Clock clock;
    /** 按回合预留并根据实际响应结算模型用量。 */
    private final UsageService usage;
    private final AgentTurnLeaseGuard leaseGuard;
    private final TaskService tasks;
    private final LlmProtocolCodec codec;

    /** 组装 Run 状态推进、模型回合检查点、事件和用量预留事务。
     * @param runs 条件推进 Run 的持久化服务
     * @param turns 创建和读取模型调用检查点
     * @param events 将回合检查点与项目事件同事务提交
     * @param mapper 生成检查点事件负载
     * @param clock 记录调用与用量时间
     * @param usage 在首次提交模型请求时预留用量
     */
    public LlmTurnCheckpointService(AgentRunRepository runs, LlmTurnRepository turns,
            ProjectEventService events, ObjectMapper mapper, Clock clock,
            UsageService usage, AgentTurnLeaseGuard leaseGuard, TaskService tasks, LlmProtocolCodec codec) {
        this.runs = runs;
        this.turns = turns;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.usage = usage;
        this.leaseGuard = leaseGuard;
        this.tasks = tasks;
        this.codec = codec;
    }

    /**
     * 模型请求发出前预留步骤和用量。重复进入同一步骤只能使用完全相同的请求与配置版本；
     * 首次插入才记录请求事件，避免恢复时重复预留用量。
     *
     * @param ownerId 服务端认证的项目所有者
     * @param projectId 本次 Run 的项目
     * @param runId 必须处于 RUNNING 的 Run
     * @param stepIndex 本次回合序号，也是检查点幂等键的一部分
     * @param configVersion Run 启动时固定的模型配置版本
     * @param configSource Run 启动时固定的模型配置来源
     * @param request 模型实际可见的版本化请求快照
     * @return 原有或新创建的请求检查点
     */
    @Transactional
    public LlmTurn reserve(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            int configVersion, String configSource, JsonNode request) {
        return reserveInternal(ownerId, projectId, runId, stepIndex, configVersion, configSource, request, null, null);
    }

    /** Production model requests are fenced before reserving usage or a checkpoint. */
    @Transactional
    public LlmTurn reserveLeased(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            int configVersion, String configSource, JsonNode request, Task lease, String workerId) {
        requireLeaseScope(projectId, runId, stepIndex, lease);
        return reserveInternal(ownerId, projectId, runId, stepIndex, configVersion, configSource, request, lease, workerId);
    }

    private LlmTurn reserveInternal(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            int configVersion, String configSource, JsonNode request, Task lease, String workerId) {
        if (stepIndex < 0 || configVersion < 1 || configSource == null
                || configSource.isBlank() || request == null || !request.isObject()) {
            throw new IllegalArgumentException("Invalid model turn request");
        }
        return events.recordChange(ownerId, projectId, () -> {
            if (lease != null) leaseGuard.requireActive(lease, workerId);
            AgentRun run = requireRunning(ownerId, projectId, runId);
            if (lease != null && run.nextStepIndex() != stepIndex) throw conflict(ApiMessage.of("api.llm-turn-checkpoint-service.run-is-not-accepting-a-model-round"));
            int pinnedVersion = run.policySnapshot().path("modelConfigVersion")
                    .asInt(-1);
            String pinnedSource = run.policySnapshot().path("modelConfigSource").asText("");
            if (pinnedVersion != configVersion || !pinnedSource.equals(configSource)) {
                throw conflict(ApiMessage.of("api.llm-turn-checkpoint-service.run-model-configuration-changed-explicit-recovery-is-required"));
            }
            boolean inserted = turns.insertRequested(projectId, runId, stepIndex,
                    configVersion, request, clock.instant());
            LlmTurn turn = turns.find(projectId, runId, stepIndex).orElseThrow();
            if (turn.modelConfigVersion() != configVersion || !turn.request().equals(request)) {
                throw conflict(ApiMessage.of("api.llm-turn-checkpoint-service.model-step-already-exists-with-a-different-request-or-config"));
            }
            if (inserted) {
                usage.reserveModelTurn(ownerId, turn);
                events.append(ownerId, projectId, event("llm.turn.requested", run, stepIndex));
            }
            return ProjectEventService.Change.unchanged(turn);
        }).value();
    }

    /**
     * 在工具执行前保存所有 generation 及 tool_call_id，结算模型用量并发出回合事件。
     * 同一响应可重放；若同一步骤已保存不同响应，则拒绝覆盖。
     *
     * @param ownerId 服务端认证的项目所有者
     * @param projectId 检查点所属项目
     * @param runId 检查点所属 Run
     * @param stepIndex 已预留请求的回合序号
     * @param configVersion 发起请求时固定的配置版本，必须与预留记录一致
     * @param response 模型返回的完整协议响应
     * @return 已落库的响应检查点
     */
    @Transactional
    public LlmTurn saveResponse(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            int configVersion, JsonNode response) {
        return saveResponseInternal(ownerId, projectId, runId, stepIndex, configVersion, response, null, null);
    }

    /** Complete public projection and protocol checkpoint atomically, while the exact lease is locked. */
    @Transactional
    public LlmTurn saveResponseLeased(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            int configVersion, JsonNode response, Task lease, String workerId) {
        requireLeaseScope(projectId, runId, stepIndex, lease);
        return saveResponseInternal(ownerId, projectId, runId, stepIndex, configVersion, response, lease, workerId);
    }

    private LlmTurn saveResponseInternal(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            int configVersion, JsonNode response, Task lease, String workerId) {
        if (response == null || !response.isObject()) {
            throw new IllegalArgumentException("Invalid model response checkpoint");
        }
        return events.recordChange(ownerId, projectId, () -> {
            if (lease != null) leaseGuard.requireActive(lease, workerId);
            AgentRun run = runs.find(ownerId, projectId, runId)
                    .orElseThrow(() -> conflict(ApiMessage.of("api.tool-execution-service.run-is-not-accessible")));
            if (lease != null && (run.status() != AgentRun.Status.RUNNING || run.nextStepIndex() != stepIndex)) {
                throw conflict(ApiMessage.of("api.llm-turn-checkpoint-service.run-is-not-accepting-a-model-round"));
            }
            LlmTurn existing = turns.find(projectId, runId, stepIndex)
                    .orElseThrow(() -> conflict(ApiMessage.of("api.llm-turn-checkpoint-service.model-request-checkpoint-is-missing")));
            if (existing.modelConfigVersion() != configVersion) {
                throw conflict(ApiMessage.of("api.llm-turn-checkpoint-service.model-config-version-changed-during-this-round"));
            }
            if (existing.status() == LlmTurn.Status.RESPONDED) {
                if (!existing.response().equals(response)) {
                    throw conflict(ApiMessage.of("api.llm-turn-checkpoint-service.model-response-was-already-recorded-differently"));
                }
                return ProjectEventService.Change.unchanged(existing);
            }
            if (lease != null) {
                String text = codec.selectedAssistant(response).getText();
                tasks.completeAgentStream(lease, workerId, text == null ? "" : text);
            }
            if (!turns.saveResponse(projectId, runId, stepIndex, response, clock.instant())) {
                throw conflict(ApiMessage.of("api.llm-turn-checkpoint-service.model-response-checkpoint-was-updated-concurrently"));
            }
            LlmTurn saved = turns.find(projectId, runId, stepIndex).orElseThrow();
            usage.settleModelTurn(ownerId, saved);
            events.append(ownerId, projectId, event("llm.turn.recorded", run, stepIndex));
            return ProjectEventService.Change.unchanged(saved);
        }).value();
    }

    private void requireLeaseScope(UUID projectId, UUID runId, int stepIndex, Task lease) {
        if (lease == null || !projectId.equals(lease.projectId()) || !runId.equals(lease.runId())
                || stepIndex != lease.input().path("stepIndex").asInt(-1)) {
            throw new IllegalArgumentException("Model checkpoint lease scope mismatch");
        }
    }

    /** 新请求只能附着于仍在运行、且属于该所有者项目的 Run。 */
    private AgentRun requireRunning(UUID ownerId, UUID projectId, UUID runId) {
        AgentRun run = runs.find(ownerId, projectId, runId)
                .orElseThrow(() -> conflict(ApiMessage.of("api.tool-execution-service.run-is-not-accessible")));
        if (run.status() != AgentRun.Status.RUNNING) {
            throw conflict(ApiMessage.of("api.llm-turn-checkpoint-service.run-is-not-accepting-a-model-round"));
        }
        return run;
    }

    /** 构造不包含提示词、响应正文或工具参数的模型回合事件。 */
    private ProjectEventService.EventDraft event(String type, AgentRun run, int stepIndex) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("runId", run.id().toString());
        payload.put("stepIndex", stepIndex);
        return new ProjectEventService.EventDraft(type, 1, run.id(),
                run.version(), payload);
    }

    /** 将步骤、配置或持久化响应冲突映射到同一稳定错误码。 */
    private ApiProblemException conflict(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "LLM_TURN_CONFLICT",
                ApiMessage.of("api.llm-turn-checkpoint-service.model-turn-conflict"), detail, false);
    }
}
