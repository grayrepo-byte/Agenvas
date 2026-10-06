package dev.agenvas.llm.application;

import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.AgentTurnLeaseGuard;
import dev.agenvas.task.domain.Task;
import dev.agenvas.provider.application.MediaCapabilityService;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 执行已保存模型响应中的单个工具调用。业务变更、工具账本和项目事件在同一事务内提交。
 */
@Service
public class ToolExecutionService {

    /** {@code create_text} 允许模型提供的字段；所有服务端管理字段均不在此集合中。 */
    private static final Set<String> CREATE_TEXT_FIELDS = Set.of("title", "text", "format");
    private static final int MAX_MEDIA_CAPABILITIES = 40;

    /** 以项目和所有者范围锁定 Run，并核对其当前执行状态。 */
    private final AgentRunRepository runs;
    /** 只从已提交的完整模型响应中查找工具调用。 */
    private final LlmTurnRepository turns;
    /** 按 Run、步骤和 tool_call_id 查询及保存幂等执行结果。 */
    private final ToolExecutionRepository ledger;
    /** 通过统一业务规则创建不可变文本产物版本。 */
    private final ArtifactService artifacts;
    /** 执行受控的产物与画布创作命令。 */
    private final CreativeArtifactToolService creative;
    /** 执行已授权的只读项目与任务查询工具。 */
    private final ReadToolService reader;
    /** 为工具执行提供项目锁顺序与同事务事件记录。 */
    private final ProjectEventService events;
    /** 在业务事务内再次核验当前 Worker 的任务租约。 */
    private final AgentTurnLeaseGuard leaseGuard;
    /** 将工具参数解析为受控 JSON，并构造结构化结果。 */
    private final ObjectMapper mapper;
    /** 为账本的开始与完成记录提供统一时间源。 */
    private final Clock clock;
    private final AgentMediaApprovalService mediaApprovals;
    private final MediaCapabilityService capabilities;

    /** 组装工具身份验证、持久化去重、业务应用服务及租约 fencing 边界。
     * @param runs 校验工具调用所属 Run 和项目权限
     * @param turns 读取已保存的模型响应，确认调用来自模型原始输出
     * @param ledger 按 Run 步骤和 toolCallId 去重并保存结果
     * @param artifacts 读取和修改不可变产物版本
     * @param creative 执行产物修订与画布布局工具
     * @param reader 执行授权范围内的只读工具
     * @param events 与工具副作用一并记录项目事件
     * @param leaseGuard 在副作用事务中核验 Agent 回合租约
     * @param mapper 解析参数并构造稳定结果 JSON
     * @param clock 为工具账本记录时间
     */
    public ToolExecutionService(AgentRunRepository runs, LlmTurnRepository turns,
            ToolExecutionRepository ledger, ArtifactService artifacts,
            CreativeArtifactToolService creative, ReadToolService reader,
            ProjectEventService events, AgentTurnLeaseGuard leaseGuard,
            ObjectMapper mapper, Clock clock, AgentMediaApprovalService mediaApprovals,
            MediaCapabilityService capabilities) {
        this.runs = runs;
        this.turns = turns;
        this.ledger = ledger;
        this.artifacts = artifacts;
        this.creative = creative;
        this.reader = reader;
        this.events = events;
        this.leaseGuard = leaseGuard;
        this.mapper = mapper;
        this.clock = clock;
        this.mediaApprovals = mediaApprovals;
        this.capabilities = capabilities;
    }

    /**
     * 执行或重放已提交模型响应中标识完全一致的工具调用，适用于不携带 Worker 租约的内部路径。
     *
     * @param context 服务端建立的所有者、项目和 Run 作用域
     * @param stepIndex 保存该响应的模型步骤序号
     * @param toolCallId 模型响应原样给出的工具调用 ID
     * @return 首次执行或幂等重放时持久化的同一结果
     */
    @Transactional
    public JsonNode execute(TrustedToolContext context, int stepIndex, String toolCallId) {
        return executeInternal(context, stepIndex, toolCallId, null, null);
    }

    /**
     * Worker 入口：在产生任何业务副作用的事务中重新锁定并核验当前租约。
     *
     * @param context 服务端建立的可信执行作用域
     * @param stepIndex 已保存模型回合的步骤序号
     * @param toolCallId 本次要执行的原始工具调用 ID
     * @param lease 当前模型回合的任务快照，项目和 Run 必须与 {@code context} 一致
     * @param workerId 当前租约持有者，必须与任务行记录匹配
     * @return 已提交工具账本中的结构化结果
     */
    @Transactional
    public JsonNode executeLeased(TrustedToolContext context, int stepIndex,
            String toolCallId, Task lease, String workerId) {
        if (lease == null || context == null || !lease.projectId().equals(context.projectId())
                || !lease.runId().equals(context.runId())) {
            throw invalid(ApiMessage.of("api.tool-execution-service.agent-turn-lease-and-trusted-run-scope-do-not-match"));
        }
        return executeInternal(context, stepIndex, toolCallId, lease, workerId);
    }

    /**
     * 校验调用身份和步骤，再按项目事件服务约定的锁顺序进入业务事务。
     *
     * @param context 服务端可信作用域，不接受模型指定的用户或项目
     * @param stepIndex 目标模型回合序号，必须非负
     * @param toolCallId 必须在已保存响应中唯一存在的调用 ID
     * @param lease Worker 路径的可选任务租约
     * @param workerId 非空租约对应的持有者标识
     * @return 事务提交后的工具结果
     */
    private JsonNode executeInternal(TrustedToolContext context, int stepIndex,
            String toolCallId, Task lease, String workerId) {
        if (context == null || stepIndex < 0 || toolCallId == null
                || toolCallId.isBlank() || toolCallId.length() > 200) {
            throw invalid(ApiMessage.of("api.tool-execution-service.invalid-run-step-or-tool-call-id"));
        }
        return events.recordChange(context.ownerId(), context.projectId(), () -> {
            if (lease != null) {
                leaseGuard.requireActive(lease, workerId);
            }
            return ProjectEventService.Change.unchanged(
                    executeLocked(context, stepIndex, toolCallId));
        }).value();
    }

    /**
     * 先锁 Run 并确认模型响应已持久化，再按调用 ID 查账；同 ID 但名称或参数摘要不同必须报冲突。
     * 新调用先占用账本，再通过白名单分派应用服务，最后返回数据库实际保存的 JSONB 结果。
     *
     * @param context 从认证和任务上下文得到的可信作用域
     * @param stepIndex 模型响应和账本共同使用的步骤序号
     * @param toolCallId 要在该响应中精确查找的调用 ID
     * @return 对应账本行中已完成的结果
     */
    private JsonNode executeLocked(TrustedToolContext context, int stepIndex, String toolCallId) {
        AgentRun run = runs.findForUpdate(context.ownerId(), context.projectId(), context.runId())
                .orElseThrow(() -> conflict(ApiMessage.of("api.tool-execution-service.run-is-not-accessible")));
        LlmTurn turn = turns.find(context.projectId(), context.runId(), stepIndex)
                .orElseThrow(() -> conflict(ApiMessage.of("api.tool-execution-service.model-turn-checkpoint-is-missing")));
        if (turn.status() != LlmTurn.Status.RESPONDED) {
            throw conflict(ApiMessage.of("api.tool-execution-service.complete-model-response-must-be-committed-before-tool-execution"));
        }
        JsonNode call = findCall(turn.response(), toolCallId);
        String toolName = call.path("name").asText();
        String arguments = call.path("arguments").asText();
        // Replay checks use the recorded raw arguments, including their field order and whitespace.
        String argumentHash = Sha256.hex(arguments);
        ToolExecution existing = ledger.find(context.projectId(), context.runId(),
                stepIndex, toolCallId).orElse(null);
        if (existing != null) {
            if (!existing.toolName().equals(toolName)
                    || !existing.argumentHash().equals(argumentHash)
                    || existing.status() != ToolExecution.Status.COMPLETED) {
                throw conflict(ApiMessage.of("api.tool-execution-service.tool-call-ledger-conflicts-with-recorded-model-response"));
            }
            return existing.result();
        }
        if (run.status() != AgentRun.Status.RUNNING) {
            throw conflict(ApiMessage.of("api.tool-execution-service.run-is-not-accepting-tool-execution"));
        }
            if (run.hasToolExecutionLimit()
                    && run.toolExecutionLimitReached(ledger.countByRun(context.projectId(), context.runId()))) {
            throw conflict(ApiMessage.of("api.tool-execution-service.run-tool-execution-budget-is-exhausted"));
        }
        if (!RunToolPolicy.allowed(run.policySnapshot()).contains(toolName)) {
            throw invalid(ApiMessage.of("api.tool-execution-service.tool-is-not-allowlisted-for-this-runtime"));
        }
        UUID operationId = UUID.randomUUID();
        if (!ledger.insertExecuting(operationId, context.projectId(), context.runId(),
                stepIndex, toolCallId, toolName, argumentHash, clock.instant())) {
            throw conflict(ApiMessage.of("api.tool-execution-service.tool-call-was-executed-concurrently"));
        }
        JsonNode result = switch (toolName) {
            case "read_project_summary" -> reader.projectSummary(context, run,
                    operationId, arguments);
            case "read_skill" -> reader.skill(run, operationId, arguments);
            case "read_skill_asset" -> reader.skillAsset(run, operationId, arguments);
            case "read_skill_resource" -> reader.skillResource(run, operationId, arguments);
            case "read_selection" -> reader.selection(run, operationId, arguments);
            case "read_artifacts" -> reader.artifacts(context, run, operationId, arguments);
            case "read_task_status" -> reader.taskStatus(context, operationId, arguments);
            case "list_media_capabilities" -> mediaCapabilities(operationId, arguments);
            case "propose_media_generation" -> mediaApprovals.propose(context, run,
                    operationId, stepIndex, toolCallId, arguments);
            case "create_text" -> createText(context, run, operationId, arguments);
            case "revise_artifact" -> creative.reviseArtifact(context, run, operationId, arguments);
            case "place_artifacts" -> creative.placeArtifacts(context, run,
                    operationId, arguments);
            case "arrange_items" -> creative.arrangeItems(context, run,
                    operationId, arguments);
            default -> throw invalid(ApiMessage.of("api.tool-execution-service.tool-is-not-allowlisted-for-this-runtime"));
        };
        if (!ledger.complete(operationId, result, clock.instant())) {
            throw new IllegalStateException("Reserved tool result could not be completed");
        }
        // JSONB 可能规范化数值节点和对象顺序；首次执行也读取落库值，使其与之后的幂等重放完全一致。
        return ledger.find(context.projectId(), context.runId(), stepIndex, toolCallId)
                .filter(saved -> saved.status() == ToolExecution.Status.COMPLETED
                        && saved.result() != null)
                .orElseThrow(() -> new IllegalStateException("Completed tool result is missing"))
                .result();
    }

    /** Exposes a bounded administrator-published catalog, with no endpoint or credential data. */
    private JsonNode mediaCapabilities(UUID operationId, String arguments) {
        JsonNode input;
        try {
            input = mapper.readTree(arguments);
        } catch (RuntimeException malformed) {
            throw invalid(ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"));
        }
        if (input == null || !input.isObject() || !input.isEmpty()) {
            throw invalid(ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"));
        }
        var result = mapper.createObjectNode().put("status", ToolResultStatus.SUCCEEDED.name())
                .put("operationId", operationId.toString()).put("userVisibleSummary", "已读取可用媒体能力");
        var available = result.putArray("capabilities");
        for (var candidate : capabilities.publishedCandidates().stream().limit(MAX_MEDIA_CAPABILITIES).toList()) {
            var entry = available.addObject().put("capabilityId", candidate.binding().capabilityId().toString())
                    .put("name", candidate.capabilityName()).put("kind", candidate.kind().name())
                    .put("minimumSeconds", candidate.minimumSeconds()).put("maximumSeconds", candidate.maximumSeconds());
            var policy = capabilities.inputPolicy(candidate.binding());
            entry.set("inputPolicy", mapper.valueToTree(policy));
            var definition = capabilities.runningHubDefinition(candidate.binding());
            if (definition != null) {
                // Only published scalar field/slot definitions; never forward Provider settings.
                entry.set("fields", mapper.valueToTree(definition.fields()));
            } else if (candidate.settings().has(dev.agenvas.provider.domain.ComfyUiWorkflowDefinition.PUBLIC_INPUTS_KEY)) {
                entry.set("fields", candidate.settings().get(dev.agenvas.provider.domain.ComfyUiWorkflowDefinition.PUBLIC_INPUTS_KEY));
            }
        }
        return result;
    }

    /**
     * 只接受标题、正文与格式三个字段；创建的文本版本必须标记 Agent 来源，并放入该 Agent 的输出区域。
     *
     * @param context 当前 Run 的可信作用域
     * @param run 用于确定输出区域的当前 Run
     * @param operationId 此次工具执行的账本操作 ID
     * @param arguments 模型提供的文本产物 JSON 参数
     * @return 新产物 ID、版本 ID 和用户可见摘要
     */
    private JsonNode createText(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        JsonNode input;
        try {
            input = mapper.readTree(arguments);
        } catch (RuntimeException exception) {
            throw invalid(ApiMessage.of("api.tool-execution-service.tool-arguments-are-not-valid-json"));
        }
        if (input == null || !input.isObject()) {
            throw invalid(ApiMessage.of("api.tool-execution-service.create-text-requires-an-object"));
        }
        for (String field : input.propertyNames()) {
            if (!CREATE_TEXT_FIELDS.contains(field)) {
                throw invalid(ApiMessage.of("api.tool-execution-service.create-text-has-an-unknown-field"));
            }
        }
        String title = requiredText(input, "title", 160);
        String text = requiredText(input, "text", 20_000);
        String format = requiredText(input, "format", 20);
        if (!Set.of("PLAIN_TEXT", "MARKDOWN").contains(format)) {
            throw invalid(ApiMessage.of("api.tool-execution-service.create-text-format-is-not-allowed"));
        }
        ObjectNode content = mapper.createObjectNode();
        content.put("format", format);
        content.put("text", text);
        ArtifactService.ArtifactView created = artifacts.createFromAgent(context.ownerId(),
                context.projectId(), context.runId(), Artifact.Kind.TEXT, title, content);
        if (created.resourceDefaultVersion().createdByKind() != ArtifactVersion.CreatedByKind.AGENT) {
            throw new IllegalStateException("Agent artifact provenance was not recorded");
        }
        creative.placeOutputs(context, run, java.util.List.of(created));
        ObjectNode result = mapper.createObjectNode();
        result.put("status", ToolResultStatus.SUCCEEDED.name());
        result.put("operationId", operationId.toString());
        result.putArray("createdIds").add(created.artifact().id().toString());
        result.putArray("updatedIds");
        result.putObject("affectedVersions")
                .put(created.artifact().id().toString(), created.resourceDefaultVersion().id().toString());
        result.putObject("artifactVersions")
                .put(created.artifact().id().toString(), created.artifact().version());
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("userVisibleSummary", "已创建文本产物");
        return result;
    }

    /**
     * 只在被选中 generation 的工具列表中精确匹配一次调用 ID，并拒绝重复 ID 或不完整的调用结构。
     *
     * @param response 已持久化的完整模型响应
     * @param toolCallId 本次执行要查找的原始调用 ID
     * @return 保存的工具调用 JSON，包含名称和原始参数字符串
     */
    private JsonNode findCall(JsonNode response, String toolCallId) {
        JsonNode generations = response.path("generations");
        if (!generations.isArray() || generations.isEmpty()) {
            throw conflict(ApiMessage.of("api.tool-execution-service.saved-model-response-lacks-generations"));
        }
        JsonNode matched = null;
        JsonNode calls = generations.get(0).path("assistant").path("toolCalls");
        if (!calls.isArray()) {
            throw conflict(ApiMessage.of("api.tool-execution-service.selected-model-generation-lacks-tool-calls"));
        }
        for (JsonNode call : calls) {
            if (toolCallId.equals(call.path("id").asText())) {
                if (matched != null || !"function".equals(call.path("type").asText())
                        || !call.path("name").isTextual()
                        || !call.path("arguments").isTextual()) {
                    throw conflict(ApiMessage.of("api.tool-execution-service.saved-model-tool-call-is-ambiguous-or-malformed"));
                }
                matched = call;
            }
        }
        if (matched == null) {
            throw conflict(ApiMessage.of("api.tool-execution-service.tool-call-id-was-not-present-in-the-saved-model"));
        }
        return matched;
    }

    /**
     * 读取 {@code create_text} 的必填文本；空值、非文本和超过字段上限的内容均拒绝。
     *
     * @param input 模型提供的文本产物参数对象
     * @param field 要读取的允许字段名
     * @param maximumLength 此字段允许的最大字符数
     * @return 通过校验的原文本
     */
    private String requiredText(JsonNode input, String field, int maximumLength) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximumLength) {
            throw invalid(ApiMessage.of("api.tool-execution-service.create-text-has-an-invalid", field));
        }
        return value.asText();
    }

    /** 将工具参数或白名单校验失败映射为稳定的 HTTP 400 错误。 */
    private ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"), detail, false);
    }

    /** 将回合状态、账本或并发冲突映射为稳定的 HTTP 409 错误。 */
    private ApiProblemException conflict(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "TOOL_EXECUTION_CONFLICT",
                ApiMessage.of("api.tool-execution-service.tool-execution-conflict"), detail, false);
    }
}
