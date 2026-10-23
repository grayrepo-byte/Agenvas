package dev.agenvas.run.application;

import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.skill.application.SkillRunService;
import dev.agenvas.llm.application.RunToolPolicy;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.run.domain.AgentConversation;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.idempotency.IdempotencyState;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 在数据库拥有的项目活动槽位下创建和推进 Run；取消与终态转换负责释放该槽位。 */
@Service
public class AgentRunService {

    /** 相同创建命令可重放的幂等记录保留时间。 */
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    /** 单次用户指令的最大字符数，避免无界内容进入模型上下文。 */
    public static final int MAX_INSTRUCTION_LENGTH = 20_000;
    /** 一次运行允许附带的画布选择数量上限。 */
    public static final int MAX_SELECTED_ITEMS = 1000;
    /** 历史列表默认每页条数。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 历史列表每页最大条数。 */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_RECOVERY_BATCH_SIZE = 100;
    private static final int MAX_CONTEXT_RUNS = 40;

    /** 校验项目归属并独占项目的活动 Run 槽位。 */
    private final ProjectService projects;
    /** 读取项目助手当前配置。 */
    private final AgentInstanceService agents;
    /** 将用户选择的画布项解析为当前项目内的意图快照。 */
    private final CanvasService canvas;
    /** 持久化 Run、幂等命令和状态版本。 */
    private final AgentRunRepository runs;
    /** 让 Run 状态变更与项目事件在同一事务提交。 */
    private final ProjectEventService events;
    /** 取消未提交任务，阻止旧 Run 继续编排。 */
    private final RunTaskCancellation taskCancellation;
    /** 在 Run 创建事务中建立首个模型回合任务。 */
    private final RunTaskCreation taskCreation;
    /** 预检并固定本次 Run 可用的模型配置。 */
    private final ChatGateway chatGateway;
    /** 构造固定上下文及策略 JSON 快照。 */
    private final ObjectMapper objectMapper;
    /** 为幂等保留期与状态事件提供一致时间。 */
    private final Clock clock;
    /** 停机开始后阻止创建新的 Run。 */
    private final ShutdownGate shutdownGate;
    private final AgentConversationService conversations;
    private final ConversationMemoryReader memoryReader;
    private final SkillRunService skills;

    /** 注入 Run 创建所需服务；事务提交由事件服务协调项目槽位、Run、任务与事件。 */
    public AgentRunService(
            ProjectService projects,
            AgentInstanceService agents,
            CanvasService canvas,
            AgentRunRepository runs,
            ProjectEventService events,
            RunTaskCancellation taskCancellation,
            RunTaskCreation taskCreation,
            ChatGateway chatGateway,
            ObjectMapper objectMapper,
            Clock clock,
            ShutdownGate shutdownGate,
            AgentConversationService conversations,
            ConversationMemoryReader memoryReader, SkillRunService skills) {
        this.projects = projects;
        this.agents = agents;
        this.canvas = canvas;
        this.runs = runs;
        this.events = events;
        this.taskCancellation = taskCancellation;
        this.taskCreation = taskCreation;
        this.chatGateway = chatGateway;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.shutdownGate = shutdownGate;
        this.conversations = conversations;
        this.memoryReader = memoryReader;
        this.skills = skills;
    }

    /** 基础创建入口；完全相同的项目级命令键与载荷重放时返回原 Run。 */
    @Transactional
    public CreateResult create(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            String requestedInstruction,
            String requestedIdempotencyKey) {
        return create(ownerId, projectId, agentId, requestedInstruction,
                requestedIdempotencyKey, null, null);
    }

    /** UI 创建入口额外核对用户预览过的 Agent 版本，配置变化时要求重新确认范围。 */
    @Transactional
    public CreateResult create(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            String requestedInstruction,
            String requestedIdempotencyKey,
            Long expectedAgentVersion) {
        return create(ownerId, projectId, agentId, requestedInstruction,
                requestedIdempotencyKey, expectedAgentVersion, null);
    }

    /** 固定用户选择的画布项作为意图；选择本身不扩展 Agent 对其他产物的访问权。 */
    @Transactional
    public CreateResult create(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            String requestedInstruction,
            String requestedIdempotencyKey,
            Long expectedAgentVersion,
            List<UUID> selectedItemIds) {
        return create(ownerId, projectId, agentId, requestedInstruction,
                requestedIdempotencyKey, expectedAgentVersion,
                selectedItemIds, null, null);
    }

    /** 附加用户预检时看到的模型配置来源和版本，创建时再次核对。 */
    @Transactional
    public CreateResult create(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            String requestedInstruction,
            String requestedIdempotencyKey,
            Long expectedAgentVersion,
            List<UUID> selectedItemIds,
            String expectedModelConfigSource,
            Integer expectedModelConfigVersion) {
        return create(ownerId, projectId, agentId, requestedInstruction,
                requestedIdempotencyKey, expectedAgentVersion,
                selectedItemIds, expectedModelConfigSource, expectedModelConfigVersion, null);
    }

    /**
     * 创建 Run 的完整入口。先按指令、Agent、选择和预检版本计算请求摘要；
     * 同键异载荷返回冲突，同键同载荷返回原 Run。项目锁内再次确认活动槽位，
     * 把 Run、首个模型任务和 Run 事件一起提交，随后追加首个任务事件。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId 将占用活动 Run 槽位的项目
     * @param agentId 项目唯一导演的稳定身份
     * @param requestedInstruction 用户指令，校验长度后固定到 Run
     * @param requestedIdempotencyKey 项目内创建 Run 的客户端幂等键
     * @param expectedAgentVersion 用户预览过的 Agent 版本；为空时不做该版本核对
     * @param selectedItemIds 可选画布选择；只作为意图，不授予额外产物权限
     * @param expectedModelConfigSource 预检时的配置来源，须与版本同时提供
     * @param expectedModelConfigVersion 预检时的配置版本，须与来源同时提供
     * @param expectedSystemPromptVersion 预检时的系统提示词版本，变化则拒绝创建
     * @return 新建或原命令重放得到的 Run，并标记是否重放
     */
    @Transactional
    public CreateResult create(
            UUID ownerId,
            UUID projectId,
            UUID agentId,
            String requestedInstruction,
            String requestedIdempotencyKey,
            Long expectedAgentVersion,
            List<UUID> selectedItemIds,
            String expectedModelConfigSource,
            Integer expectedModelConfigVersion,
            Integer expectedSystemPromptVersion) {
        return create(ownerId, projectId, agentId, requestedInstruction, requestedIdempotencyKey,
                expectedAgentVersion, selectedItemIds, expectedModelConfigSource,
                expectedModelConfigVersion, expectedSystemPromptVersion, null, null);
    }

    /** 同会话新消息仍创建独立 Run；创建时冻结历史版本，幂等重放不重新取当前会话。 */
    @Transactional
    public CreateResult create(UUID ownerId, UUID projectId, UUID agentId,
            String requestedInstruction, String requestedIdempotencyKey, Long expectedAgentVersion,
            List<UUID> selectedItemIds, String expectedModelConfigSource,
            Integer expectedModelConfigVersion, Integer expectedSystemPromptVersion,
            UUID requestedConversationId, Long expectedConversationVersion) {
        return create(ownerId, projectId, agentId, requestedInstruction, requestedIdempotencyKey,
                expectedAgentVersion, selectedItemIds, expectedModelConfigSource, expectedModelConfigVersion,
                expectedSystemPromptVersion, requestedConversationId, expectedConversationVersion, null);
    }

    @Transactional
    public CreateResult create(UUID ownerId, UUID projectId, UUID agentId,
            String requestedInstruction, String requestedIdempotencyKey, Long expectedAgentVersion,
            List<UUID> selectedItemIds, String expectedModelConfigSource,
            Integer expectedModelConfigVersion, Integer expectedSystemPromptVersion,
            UUID requestedConversationId, Long expectedConversationVersion, SkillRunService.Selection skillSelection) {
        shutdownGate.requireAcceptingRuns();
        String instruction = validateInstruction(requestedInstruction);
        String key = validateIdempotencyKey(requestedIdempotencyKey);
        List<UUID> selection = validateSelection(selectedItemIds);
        if ((expectedModelConfigSource == null) != (expectedModelConfigVersion == null)) {
            throw validation(ApiMessage.of("api.agent-run-service.model-configuration-source-and-version-must-be-submitted-together"));
        }
        String scope = "project:" + projectId + ":create-run";
        String requestFingerprint = agentId + "\n" + instruction + "\n"
                + expectedAgentVersion + "\n" + selection;
        if (expectedModelConfigSource != null) {
            requestFingerprint += "\n" + expectedModelConfigSource + "\n"
                    + expectedModelConfigVersion;
        }
        if (expectedSystemPromptVersion != null) {
            requestFingerprint += "\nsystem-prompt:" + expectedSystemPromptVersion;
        }
        // Omitted conversation fields mean "use current" at acceptance. Replays hash
        // that submitted intent, so a later current-conversation change cannot duplicate it.
        if (requestedConversationId != null) requestFingerprint += "\nconversation:" + requestedConversationId;
        if (expectedConversationVersion != null) requestFingerprint += "\nconversation-version:" + expectedConversationVersion;
        if (skillSelection != null) requestFingerprint += "\nskill:" + objectMapper.valueToTree(skillSelection);
        String requestHash = Sha256.hex(requestFingerprint);
        Instant now = clock.instant();
        boolean reserved = runs.reserveIdempotency(
                ownerId, scope, key, requestHash, now.plus(IDEMPOTENCY_RETENTION), now);
        if (!reserved) {
            AgentRunRepository.IdempotencyRecord existing = runs.findIdempotency(ownerId, scope, key)
                    .orElseThrow(this::idempotencyInProgress);
            if (!existing.requestHash().equals(requestHash)) {
                throw new ApiProblemException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        ApiMessage.of("api.artifact-service.idempotent-keys-have-been-used-for-different-requests"),
                        ApiMessage.of("api.agent-run-service.please-use-new-idempotency-key-for-different-agents-or-commands"),
                        false);
            }
            if (existing.state() != IdempotencyState.COMPLETED
                    || existing.resourceId() == null) {
                throw idempotencyInProgress();
            }
            return new CreateResult(
                    require(ownerId, projectId, existing.resourceId()), true);
        }

        Project project = projects.requireActiveProject(ownerId, projectId);
        AgentInstance agent = agents.get(ownerId, projectId, agentId);
        if (expectedAgentVersion != null && agent.version() != expectedAgentVersion) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "AGENT_VERSION_CONFLICT",
                    ApiMessage.of("api.agent-run-service.agent-configuration-has-changed"), ApiMessage.of("api.agent-run-service.the-input-or-instructions-may-have-changed-please-recheck-the"), false);
        }
        ObjectNode policy = policySnapshot();
        if (expectedModelConfigSource != null
                && (!expectedModelConfigSource.equals(
                        policy.path("modelConfigSource").asText())
                    || expectedModelConfigVersion != policy.path("modelConfigVersion").asInt())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "MODEL_CONFIG_CONFLICT",
                    ApiMessage.of("api.llm-provider-config-service.model-configuration-has-changed"), ApiMessage.of("api.agent-run-service.the-model-configuration-that-will-be-used-is-different-from"), false);
        }
        if (expectedSystemPromptVersion != null
                && expectedSystemPromptVersion != policy.path("systemPromptVersion").asInt(-1)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "SYSTEM_PROMPT_CONFLICT",
                    ApiMessage.of("api.agent-run-service.the-system-prompt-word-has-changed"), ApiMessage.of("api.agent-run-service.the-running-rules-are-different-from-the-pre-run-preview"), false);
        }
        UUID runId = UUID.randomUUID();
        CreatedRun created = shutdownGate.admitRun(() -> events.recordChange(ownerId, projectId, () -> {
                    shutdownGate.requireAcceptingRuns();
                    projects.requireAvailableRunSlot(ownerId, projectId);
                    AgentInstance pinnedAgent = agents.get(ownerId, projectId, agentId);
                    if (pinnedAgent.version() != agent.version()) {
                        throw new ApiProblemException(HttpStatus.CONFLICT, "AGENT_VERSION_CONFLICT",
                                ApiMessage.of("api.agent-run-service.agent-configuration-has-changed"), ApiMessage.of("api.agent-run-service.the-input-or-instructions-may-have-changed-please-recheck-the"), false);
                    }
                    AgentConversation conversation = conversations.resolveOrCreate(ownerId, projectId,
                            agentId, requestedConversationId);
                    if (expectedConversationVersion != null && expectedConversationVersion != conversation.version()) {
                        throw new ApiProblemException(HttpStatus.CONFLICT, "CONVERSATION_VERSION_CONFLICT",
                                ApiMessage.of("api.agent-run-service.session-has-changed"), ApiMessage.of("api.agent-run-service.there-is-a-new-message-in-the-conversation-please-recheck"), false);
                    }
                    ObjectNode snapshot = contextSnapshot(ownerId, agent, project, selection);
                    snapshot.set("conversationMemory", objectMapper.valueToTree(conversationMemory(projectId, conversation)));
                    snapshot.put("conversationId", conversation.id().toString());
                    snapshot.put("conversationHistoryThroughTurn", conversation.turnCount());
                    skills.freezeIntoRun(ownerId, projectId, pinnedAgent, runId, skillSelection, snapshot);
                    policy.set("allowedTools", objectMapper.valueToTree(RunToolPolicy.current(
                            !snapshot.path("creativeSkills").isEmpty(),
                            java.util.stream.StreamSupport.stream(snapshot.path("creativeSkills").spliterator(), false)
                                    .anyMatch(skill -> !skill.path("resources").isEmpty()),
                            java.util.stream.StreamSupport.stream(snapshot.path("creativeSkills").spliterator(), false)
                                    .anyMatch(skill -> !skill.path("assets").isEmpty()))));
                    AgentConversation advanced = conversations.appendTurn(ownerId, conversation,
                            instruction, expectedConversationVersion, now);
                    AgentRun run = new AgentRun(runId, projectId, agentId, conversation.id(),
                            advanced.turnCount(), ownerId, AgentRun.Status.QUEUED, instruction,
                            snapshot, policy, agent.profileVersion(), 0, 0, now, now, null);
                    runs.create(run);
                    projects.assignRunSlot(ownerId, projectId, runId);
                    UUID firstTaskId = taskCreation.createInitialTurn(projectId, runId, now);
                    if (!runs.completeIdempotency(
                            ownerId,
                            scope,
                            key,
                            requestHash,
                            runId,
                            "{\"runId\":\"" + runId + "\"}",
                            now)) {
                        throw new IllegalStateException(
                                "Failed to complete reserved Run idempotency record");
                    }
                    return ProjectEventService.Change.changed(new CreatedRun(run, firstTaskId), runEvent(run));
                })
                .value());
        conversations.publishChange(ownerId, projectId, agentId, created.run().conversationId());
        ObjectNode taskPayload = objectMapper.createObjectNode();
        taskPayload.put("taskId", created.firstTaskId().toString());
        taskPayload.put("status", Task.Status.READY.name());
        taskPayload.put("kind", Task.Kind.AGENT_TURN.name());
        events.append(ownerId, projectId, new ProjectEventService.EventDraft(
                "task.status.changed", 1, created.firstTaskId(), 0, taskPayload));
        return new CreateResult(created.run(), false);
    }

    /** 按项目所有者读取 Run；不存在与越权使用相同的 404 响应。 */
    @Transactional(readOnly = true)
    public AgentRun get(UUID ownerId, UUID projectId, UUID runId) {
        projects.get(ownerId, projectId);
        return require(ownerId, projectId, runId);
    }

    /** Internal bounded recovery scan; it does not change frozen policies or restart any work. */
    @Transactional(readOnly = true)
    public List<BlockedRunCandidate> blockedRecoveryCandidates(UUID afterId, int limit) {
        if (limit < 1 || limit > MAX_RECOVERY_BATCH_SIZE) throw new IllegalArgumentException("Invalid recovery batch size");
        return runs.blockedRecoveryCandidates(afterId, limit);
    }

    /** A missed local-task event must not leave an otherwise finished tool round waiting forever. */
    @Transactional(readOnly = true)
    public List<WaitingRunCandidate> waitingRecoveryCandidates(UUID afterId, int limit) {
        if (limit < 1 || limit > MAX_RECOVERY_BATCH_SIZE) throw new IllegalArgumentException("Invalid recovery batch size");
        return runs.waitingRecoveryCandidates(afterId, limit);
    }

    /** 会话归属核验先于分页；游标为单调消息序号，不由墙钟时间决定顺序。 */
    @Transactional(readOnly = true)
    public RunPage listConversation(UUID ownerId, UUID projectId, UUID agentId, UUID conversationId,
            String encodedCursor, Integer requestedLimit) {
        conversations.get(ownerId, projectId, agentId, conversationId);
        int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw validation(ApiMessage.of("api.project-service.limit-must-be-between-1-and-100"));
        Long beforeTurn = null;
        if (encodedCursor != null) {
            try {
                if (encodedCursor.length() > 40) throw new IllegalArgumentException();
                beforeTurn = Long.parseLong(new String(Base64.getUrlDecoder().decode(encodedCursor),
                        StandardCharsets.UTF_8));
                if (beforeTurn < 1) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw validation(ApiMessage.of("api.project-service.the-cursor-is-invalid-or-corrupt")); }
        }
        List<AgentRun> rows = runs.listConversation(ownerId, projectId, agentId, conversationId, beforeTurn, limit + 1);
        boolean more = rows.size() > limit;
        List<AgentRun> items = more ? rows.subList(0, limit) : rows;
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                Long.toString(items.getLast().conversationTurn()).getBytes(StandardCharsets.UTF_8)) : null;
        return new RunPage(List.copyOf(items), next);
    }

    /** 同一会话的一页运行历史；nextCursor 为空表示没有下一页。
     * @param items 当前页的 Run 记录
     * @param nextCursor 下一页位置；没有更多结果时为空
     */
    public record RunPage(List<AgentRun> items, String nextCursor) {}

    /** 模型调用前展示助手配置、会话输出版本和策略，供用户核对运行范围。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public RunPreflight preflight(UUID ownerId, UUID projectId, UUID agentId) {
        return preflight(ownerId, projectId, agentId, null);
    }

    /** 预览同一会话已提交的公开记忆；受理时仍在项目锁下再次核对版本。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public RunPreflight preflight(UUID ownerId, UUID projectId, UUID agentId, UUID conversationId) {
        return preflight(ownerId, projectId, agentId, conversationId, null);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public RunPreflight preflight(UUID ownerId, UUID projectId, UUID agentId, UUID conversationId, SkillRunService.Selection selection) {
        projects.requireRunSlotAvailableSnapshot(ownerId, projectId);
        AgentInstance agent = agents.get(ownerId, projectId, agentId);
        AgentConversation conversation = conversations.resolve(ownerId, projectId, agentId, conversationId);
        ConversationMemoryReader.ConversationMemory memory = conversationMemory(projectId, conversation);
        var skill = skills.preview(ownerId, projectId, agentId, selection);
        ObjectNode policy = policySnapshot();
        policy.set("allowedTools", objectMapper.valueToTree(RunToolPolicy.current(
                !skill.isEmpty(), skill.stream().anyMatch(item -> !item.resources().isEmpty()),
                skill.stream().anyMatch(item -> !item.assets().isEmpty()))));
        ChatGateway.ModelDetails model = chatGateway.modelDetails();
        return new RunPreflight(agent.id(), agent.version(), agent.name(),
                agent.instruction(), model.available(), model.providerAdapter(),
                model.modelId(), model.toolCalling(), policy,
                conversation == null ? null : conversation.id(), conversation == null ? null : conversation.version(),
                conversation == null ? 0 : conversation.turnCount(), memory.truncated(), skill);
    }

    /** 运行前预览；不包含凭证、端点或模型私有消息。
     * @param agentId 即将运行的 Agent ID
     * @param agentVersion 用户预览过的 Agent 配置版本
     * @param agentName Agent 展示名称
     * @param agentInstruction Agent 固定指令
     * @param modelAvailable 当前模型配置是否可用
     * @param providerAdapter 对外展示的 Provider 适配器名
     * @param modelId 对外展示的模型 ID
     * @param toolCalling 当前模型是否声明支持工具调用
     * @param policySnapshot 创建 Run 时将固定的策略快照
     * @param conversationId 已选择的会话；从未创建会话时为空
     * @param conversationVersion 用户确认的历史版本；创建 Run 时再次比较
     * @param conversationTurnCount 会话内已受理的 Run 数
     * @param memoryTruncated 历史公开文本是否触及上下文限额
     */
    public record RunPreflight(UUID agentId, long agentVersion, String agentName,
            String agentInstruction,
            boolean modelAvailable, String providerAdapter, String modelId,
            boolean toolCalling, ObjectNode policySnapshot, UUID conversationId, Long conversationVersion,
            long conversationTurnCount, boolean memoryTruncated, List<SkillRunService.Summary> creativeSkills) {}

    /**
     * 在项目事务内按预期版本校验 Run 状态机；终态释放项目槽位，取消请求同时取消未提交任务。
     * 相同目标状态可重放，但版本不匹配仍返回冲突。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId Run 所属项目
     * @param runId 要变更的 Run
     * @param expectedVersion 调用方读取到的 Run 版本，防止覆盖并发状态变化
     * @param target 目标状态，必须属于当前状态允许的转换集合
     * @return 已持久化的最新 Run
     */
    @Transactional
    public AgentRun transition(
            UUID ownerId,
            UUID projectId,
            UUID runId,
            long expectedVersion,
            AgentRun.Status target) {
        return events.recordChange(ownerId, projectId, () -> {
                    AgentRun current = runs.findForUpdate(ownerId, projectId, runId)
                            .orElseThrow(this::notFound);
                    if (current.version() != expectedVersion) {
                        throw versionConflict();
                    }
                    if (current.status() == target) {
                        return ProjectEventService.Change.unchanged(current);
                    }
                    if (!current.status().canTransitionTo(target)) {
                        throw new ApiProblemException(
                                HttpStatus.CONFLICT,
                                "RUN_STATE_CONFLICT",
                                ApiMessage.of("api.agent-run-service.run-status-cannot-be-converted"),
                                ApiMessage.of("api.agent-run-service.cannot-transition-from-to", current.status(), target),
                                false);
                    }
                    AgentRun updated = updateStatusLocked(
                            ownerId, projectId, runId, expectedVersion, target);
                    if (target == AgentRun.Status.CANCEL_REQUESTED) {
                        cancelUnsubmittedTasks(projectId, runId);
                        events.append(ownerId, projectId, runEvent(updated));
                        return ProjectEventService.Change.unchanged(updated);
                    }
                    return ProjectEventService.Change.changed(updated, runEvent(updated));
                })
                .value();
    }

    /**
     * 按 Run 版本与当前步骤序号同时比较并前移模型游标，不限制整个 Run 的累计回合数。
     * 调用方应与下一回合任务创建置于同一业务事务。
     */
    @Transactional
    public AgentRun advanceStep(UUID ownerId, UUID projectId, UUID runId,
            long expectedVersion, int expectedStepIndex) {
        if (expectedStepIndex < 0) {
            throw new IllegalArgumentException("Model step must be non-negative");
        }
        return events.recordChange(ownerId, projectId, () -> {
            AgentRun current = runs.findForUpdate(ownerId, projectId, runId)
                    .orElseThrow(this::notFound);
            if (current.version() != expectedVersion
                    || current.nextStepIndex() != expectedStepIndex
                    || (current.status() != AgentRun.Status.RUNNING
                            && current.status() != AgentRun.Status.WAITING_TASKS)
                    || !runs.advanceStep(ownerId, projectId, runId, expectedVersion,
                            expectedStepIndex, clock.instant())) {
                throw versionConflict();
            }
            AgentRun advanced = require(ownerId, projectId, runId);
            return ProjectEventService.Change.changed(advanced, runEvent(advanced));
        }).value();
    }

    /** 持久化取消意图、取消未提交任务并转入 CANCELED，最后释放项目活动槽位。 */
    @Transactional
    public AgentRun cancel(UUID ownerId, UUID projectId, UUID runId) {
        return events.recordChange(ownerId, projectId, () -> {
                    AgentRun current = runs.findForUpdate(ownerId, projectId, runId)
                            .orElseThrow(this::notFound);
                    if (current.status().terminal()) {
                        return ProjectEventService.Change.unchanged(current);
                    }
                    if (current.status() != AgentRun.Status.CANCEL_REQUESTED) {
                        current = updateStatusLocked(
                                ownerId,
                                projectId,
                                runId,
                                current.version(),
                                AgentRun.Status.CANCEL_REQUESTED);
                    }
                    cancelUnsubmittedTasks(projectId, runId);
                    AgentRun canceled = updateStatusLocked(
                            ownerId,
                            projectId,
                            runId,
                            current.version(),
                            AgentRun.Status.CANCELED);
                    events.append(ownerId, projectId, runEvent(canceled));
                    return ProjectEventService.Change.unchanged(canceled);
                })
                .value();
    }

    /** 取消本 Run 尚未提交的任务；外部已受理的请求仍可能产生费用。 */
    private void cancelUnsubmittedTasks(UUID projectId, UUID runId) {
        taskCancellation.requestCancellation(projectId, runId, clock.instant());
    }

    /** 调用前须持有项目锁；CAS 更新状态后仅在终态释放该 Run 占用的槽位。 */
    private AgentRun updateStatusLocked(
            UUID ownerId,
            UUID projectId,
            UUID runId,
            long expectedVersion,
            AgentRun.Status target) {
        Instant now = clock.instant();
        if (!runs.updateStatus(
                ownerId,
                projectId,
                runId,
                expectedVersion,
                target,
                now,
                target.terminal() ? now : null)) {
            throw versionConflict();
        }
        if (target.terminal()) {
            projects.releaseRunSlot(ownerId, projectId, runId);
        }
        return require(ownerId, projectId, runId);
    }

    /** Run 事件只携带可核实的状态、ID、步骤游标和版本，不包含指令或模型内容。 */
    private ProjectEventService.EventDraft runEvent(AgentRun run) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", run.status().name());
        payload.put("runId", run.id().toString());
        payload.put("nextStepIndex", run.nextStepIndex());
        return new ProjectEventService.EventDraft(
                "agent.run.changed", 1, run.id(), run.version(), payload);
    }

    /** 只冻结已核验会话的终态 Run 公开文本与动作；历史内容不授予产物权限。 */
    private ConversationMemoryReader.ConversationMemory conversationMemory(UUID projectId,
            AgentConversation conversation) {
        List<UUID> priorIds = conversation == null ? List.of() : runs.contextRunIds(projectId,
                conversation.id(), conversation.turnCount(), MAX_CONTEXT_RUNS);
        long priorCount = conversation == null ? 0 : runs.contextRunCount(projectId,
                conversation.id(), conversation.turnCount());
        ConversationMemoryReader.ConversationMemory memory = memoryReader.read(projectId, priorIds);
        return new ConversationMemoryReader.ConversationMemory(memory.entries(),
                memory.truncated() || priorCount > priorIds.size(),
                (int) Math.min(Integer.MAX_VALUE, priorCount));
    }

    /** 冻结创作配置和用户选择的任务重点；画布访问按当前项目内容校验。 */
    private ObjectNode contextSnapshot(UUID ownerId, AgentInstance agent, Project project,
            List<UUID> selectedItemIds) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("projectName", project.name());
        snapshot.put("aspectRatio", project.aspectRatio().name());
        snapshot.put("agentId", agent.id().toString());
        snapshot.put("agentVersion", agent.version());
        snapshot.put("profileKey", agent.profileKey());
        snapshot.put("profileVersion", agent.profileVersion());
        snapshot.put("agentName", agent.name());
        snapshot.put("agentInstruction", agent.instruction());
        ArrayNode selection = snapshot.putArray("selection");
        List<CanvasService.CanvasEntry> items = selectedItemIds.isEmpty()
                ? List.of() : canvas.list(ownerId, project.id());
        for (UUID itemId : selectedItemIds) {
            CanvasService.CanvasEntry selected = items.stream()
                    .filter(entry -> entry.item().id().equals(itemId))
                    .findFirst().orElseThrow(() -> validation(
                            ApiMessage.of("api.agent-run-service.the-selected-card-no-longer-exists-or-does-not-belong")));
            CanvasItem item = selected.item();
            ObjectNode reference = selection.addObject();
            reference.put("itemId", item.id().toString());
            reference.put("subjectType", item.subjectType().name());
            reference.put("subjectId", item.subjectId().toString());
            if (selected.artifact() != null) {
                // Text owns its current Artifact version; media owns the node's exact selection, including an empty draft.
                ArtifactVersion selectedVersion = selected.artifact().artifact().kind() == Artifact.Kind.TEXT
                        ? selected.artifact().resourceDefaultVersion() : selected.selectedVersion();
                if (selectedVersion != null) {
                    reference.put("versionId", selectedVersion.id().toString());
                }
                reference.put("kind", selected.artifact().artifact().kind().name());
            }
        }
        return snapshot;
    }

    /** 把本次 Run 的模型配置版本及回合、工具预算写入不可变策略快照。 */
    private ObjectNode policySnapshot() {
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("schemaVersion", RunToolPolicy.CURRENT_SCHEMA_VERSION);
        policy.put("toolPolicyVersion", RunToolPolicy.CURRENT_VERSION);
        policy.set("allowedTools", objectMapper.valueToTree(RunToolPolicy.CURRENT));
        policy.put("systemPromptVersion", dev.agenvas.llm.application.InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION);
        ChatGateway.ConfigIdentity model = chatGateway.configIdentity();
        policy.put("modelConfigVersion", model.version());
        policy.put("modelConfigSource", model.source());
        policy.putNull("maxModelTurns");
        policy.putNull("maxToolExecutions");
        return policy;
    }

    /** 在所有者和项目范围内读取 Run，避免泄露其他项目资源是否存在。 */
    private AgentRun require(UUID ownerId, UUID projectId, UUID runId) {
        return runs.find(ownerId, projectId, runId).orElseThrow(this::notFound);
    }

    /** 去除指令首尾空白并限制为 1 至 20,000 字符。 */
    private String validateInstruction(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_INSTRUCTION_LENGTH) {
            throw validation(ApiMessage.of("api.agent-run-service.run-instructions-must-be-between-1-and-20-000-characters"));
        }
        return normalized;
    }

    /** 在计算幂等摘要前拒绝空 ID、重复 ID 或超过 20 个的画布选择。 */
    private List<UUID> validateSelection(List<UUID> requested) {
        if (requested == null) return List.of();
        if (requested.size() > MAX_SELECTED_ITEMS
                || requested.stream().anyMatch(Objects::isNull)
                || new HashSet<>(requested).size() != requested.size()) {
            throw validation(ApiMessage.of("api.agent-run-service.a-maximum-of-1000-cards-can-be-selected"));
        }
        return List.copyOf(requested);
    }

    /** 去除客户端幂等键首尾空白并限制为 1 至 200 字符。 */
    private String validateIdempotencyKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw validation(ApiMessage.of("api.artifact-service.idempotency-key-must-be-1-to-200-characters"));
        }
        return normalized;
    }

    /** 构造 Run 不存在或不属于当前所有者时的 404 响应。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                ApiMessage.of("api.agent-run-service.run-does-not-exist"),
                ApiMessage.of("api.agent-run-service.run-does-not-exist-or-the-current-user-does-not"),
                false);
    }

    /** 构造 Run 乐观版本已变化或步骤 CAS 失败时的 409 响应。 */
    private ApiProblemException versionConflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "RUN_VERSION_CONFLICT",
                ApiMessage.of("api.agent-run-service.run-status-updated"),
                ApiMessage.of("api.agent-run-service.run-has-been-modified-by-other-executors-please-read-the"),
                false);
    }

    /** 构造同一幂等键仍在处理、客户端可重试的 409 响应。 */
    private ApiProblemException idempotencyInProgress() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_IN_PROGRESS",
                ApiMessage.of("api.artifact-service.the-same-request-is-being-processed"),
                ApiMessage.of("api.artifact-service.please-try-again-later-with-the-same-idempotency-key"),
                true);
    }

    /** 构造 Run 创建输入或状态迁移不符合业务规则时的 400 响应。 */
    private ApiProblemException validation(ApiMessage detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                ApiMessage.of("api.agent-run-service.invalid-run-request"),
                detail,
                false);
    }

    /** 区分新建 Run 与完全相同命令的重放，以决定 HTTP 响应语义。
     * @param run 新创建或通过幂等键找到的持久化 Run
     * @param replayed 是否为相同请求的幂等重放
     */
    public record CreateResult(AgentRun run, boolean replayed) {}

    /** 在同一个项目事件序号锁下提交的 Run 和首个模型回合任务。
     * @param run 已创建的 Run
     * @param firstTaskId 首个模型回合任务 ID
     */
    private record CreatedRun(AgentRun run, UUID firstTaskId) {}
}
