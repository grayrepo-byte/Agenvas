package dev.agenvas.usage.application;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.LlmTurn;
import dev.agenvas.llm.application.LlmModeProperties;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.domain.UsageEntry;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 记录模型、媒体任务和导出的预留与结算；缺少真实价格时保持 UNKNOWN，不伪造金额。 */
@Service
public class UsageService {
    private static final int MONEY_SCALE = 6;

    /** 以 operationKey 唯一约束保证每笔用量账目最多写入一次。 */
    private final UsageRepository ledger;
    /** 用于项目归属检查，保护用量历史查询。 */
    private final ProjectService projects;
    /** 将账本变化与项目事件放在同一事务提交。 */
    private final ProjectEventService events;
    /** 构造 quantity JSON，避免账本字段由调用方任意拼装。 */
    private final ObjectMapper mapper;
    /** 为账目时间戳提供可注入的时钟。 */
    private final Clock clock;
    /** 区分 Mock 与真实 Provider 来源，不据此推导价格。 */
    private final LlmModeProperties llmMode;

    /** 注入账本、事件和时钟；写入方法要求由调用方处于同一事务中。 */
    public UsageService(UsageRepository ledger, ProjectService projects,
            ProjectEventService events, ObjectMapper mapper, Clock clock,
            LlmModeProperties llmMode) {
        this.ledger = ledger;
        this.projects = projects;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.llmMode = llmMode;
    }

    /** 持久化的 REQUESTED 检查点对应一次模型请求预留。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveModelTurn(UUID ownerId, LlmTurn turn) {
        if (turn.status() != LlmTurn.Status.REQUESTED) {
            throw new IllegalArgumentException("Model reservation requires REQUESTED checkpoint");
        }
        String source = llmMode.mode() == LlmModeProperties.Mode.MOCK
                ? "MOCK_UNPRICED" : "PROVIDER_UNPRICED";
        persist(ownerId, modelEntry(turn, UsageEntry.EntryType.RESERVATION,
                source, null, null, null));
    }

    /** 模型响应已保存后结算同一请求；仅在 Provider 报告时记录 token 数。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleModelTurn(UUID ownerId, LlmTurn turn) {
        if (turn.status() != LlmTurn.Status.RESPONDED) {
            throw new IllegalArgumentException("Model settlement requires RESPONDED checkpoint");
        }
        String key = modelOperationKey(turn, "reserve");
        UsageEntry reservation = ledger.findByOperationKey(key).orElseThrow(() ->
                new IllegalStateException("Model turn has no usage reservation"));
        JsonNode usage = turn.response().path("metadata").path("usage");
        String modelId = turn.response().path("metadata").path("model").asText(null);
        persist(ownerId, modelEntry(turn, UsageEntry.EntryType.SETTLEMENT,
                reservation.costSource(), tokenCount(usage, "promptTokens"),
                tokenCount(usage, "completionTokens"), modelId));
    }

    /** 根据已持久化模型回合构造单次请求的预留或结算账目。 */
    private UsageEntry modelEntry(LlmTurn turn, UsageEntry.EntryType type, String source,
            Integer inputTokens, Integer outputTokens, String modelId) {
        ObjectNode quantity = mapper.createObjectNode();
        quantity.put("imageCount", 0);
        quantity.put("videoCount", 0);
        quantity.put("videoSeconds", "0.000");
        quantity.put("exportCount", 0);
        quantity.put("llmRequestCount", 1);
        putNullableToken(quantity, "inputTokens", inputTokens);
        putNullableToken(quantity, "outputTokens", outputTokens);
        return new UsageEntry(UUID.randomUUID(), turn.projectId(), turn.runId(), null,
                modelOperationKey(turn, type == UsageEntry.EntryType.RESERVATION
                        ? "reserve" : "settle"), type, quantity, null, null, null,
                UsageEntry.CostStatus.UNKNOWN, source, turn.modelConfigVersion(),
                null, modelId == null || modelId.isBlank() || modelId.length() > 160
                        ? null : modelId, clock.instant());
    }

    /** 用 Run、步骤和阶段组成模型账目幂等键，区分预留与结算。 */
    private String modelOperationKey(LlmTurn turn, String stage) {
        return "llm:" + turn.runId() + ":" + turn.stepIndex() + ":" + stage;
    }

    /** A direct text-card Task reserves one configured-model request before entering the queue. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveDirectTextTask(UUID ownerId, Task task) {
        requireDirectText(task);
        String source = llmMode.mode() == LlmModeProperties.Mode.MOCK
                ? "MOCK_UNPRICED" : "PROVIDER_UNPRICED";
        persist(ownerId, directTextEntry(task, UsageEntry.EntryType.RESERVATION,
                source, null, null, task.input().path("modelId").asText(null), "reserve"));
    }

    /** The complete persisted model response settles the direct request before artifact mutation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleDirectTextTask(UUID ownerId, Task task, JsonNode response) {
        requireDirectText(task);
        UsageEntry reservation = ledger.findByOperationKey(
                "llm-task:" + task.id() + ":reserve").orElseThrow(() ->
                new IllegalStateException("Direct text Task has no usage reservation"));
        JsonNode metadata = response.path("response").path("metadata");
        JsonNode reported = metadata.path("usage");
        persist(ownerId, directTextEntry(task, UsageEntry.EntryType.SETTLEMENT,
                reservation.costSource(), tokenCount(reported, "promptTokens"),
                tokenCount(reported, "completionTokens"),
                metadata.path("model").asText(null), "settle"));
    }

    /** A request rejected before any durable model response releases its unknown-cost reservation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseDirectTextTask(UUID ownerId, Task task) {
        requireDirectText(task);
        String prefix = "llm-task:" + task.id();
        UsageEntry reservation = ledger.findByOperationKey(prefix + ":reserve").orElseThrow(() ->
                new IllegalStateException("Direct text Task has no usage reservation"));
        if (ledger.findByOperationKey(prefix + ":settle").isPresent()) return;
        persist(ownerId, directTextEntry(task, UsageEntry.EntryType.RELEASE,
                reservation.costSource(), null, null,
                task.input().path("modelId").asText(null), "release"));
    }

    private UsageEntry directTextEntry(Task task, UsageEntry.EntryType type, String source,
            Integer inputTokens, Integer outputTokens, String modelId, String stage) {
        ObjectNode quantity = mapper.createObjectNode();
        quantity.put("imageCount", 0);
        quantity.put("videoCount", 0);
        quantity.put("videoSeconds", "0");
        quantity.put("exportCount", 0);
        quantity.put("llmRequestCount", 1);
        putNullableToken(quantity, "inputTokens", inputTokens);
        putNullableToken(quantity, "outputTokens", outputTokens);
        return new UsageEntry(UUID.randomUUID(), task.projectId(), null, task.id(),
                "llm-task:" + task.id() + ":" + stage, type, quantity,
                null, null, null, UsageEntry.CostStatus.UNKNOWN, source,
                task.input().path("modelConfigVersion").asInt(), null,
                modelId == null || modelId.isBlank() || modelId.length() > 160 ? null : modelId,
                clock.instant());
    }

    private void requireDirectText(Task task) {
        if (task.kind() != Task.Kind.TEXT_GENERATION || task.runId() != null) {
            throw new IllegalArgumentException("Direct text usage requires a project Task");
        }
    }

    /** 仅接受非负且可表示为 int 的 Provider token 计数，其他值记为未知。 */
    private Integer tokenCount(JsonNode usage, String field) {
        JsonNode value = usage.path(field);
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0
                ? value.asInt() : null;
    }

    /** 保留缺失 token 数量的未知语义，不将其写成零。 */
    private void putNullableToken(ObjectNode quantity, String field, Integer tokens) {
        if (tokens == null) quantity.putNull(field);
        else quantity.put(field, tokens);
    }

    /** 在已鉴权的项目事务中，每创建一个直接媒体任务后写入对应预留。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveMediaTask(UUID ownerId, Task task, String costSource) {
        boolean directMedia = task.runId() == null
                && (task.kind() == Task.Kind.AUDIO_GENERATION || task.kind() == Task.Kind.IMAGE_GENERATION
                        || task.kind() == Task.Kind.VIDEO_GENERATION);
        if (!directMedia) {
            throw new IllegalArgumentException("Media reservation requires a direct media Task");
        }
        persist(ownerId, entry(task, UsageEntry.EntryType.RESERVATION,
                costSource, "media:" + task.id() + ":reserve"));
    }

    /** 仅在带 fencing 校验的媒体结果同事务提交后结算。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleMediaTask(UUID ownerId, Task task) {
        if (task.kind() != Task.Kind.AUDIO_GENERATION && task.kind() != Task.Kind.IMAGE_GENERATION
                && task.kind() != Task.Kind.VIDEO_GENERATION) return;
        UsageEntry reservation = ledger.findByOperationKey(
                "media:" + task.id() + ":reserve").orElseThrow(() ->
                new IllegalStateException("Direct media Task has no media usage reservation"));
        persist(ownerId, entry(task, UsageEntry.EntryType.SETTLEMENT,
                reservation.costSource(), "media:" + task.id() + ":settle"));
    }

    /** 调用方确认任务未到达提交检查点后，释放媒体任务的预留。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseUnsubmittedMediaTask(UUID ownerId, Task task) {
        if ((task.kind() != Task.Kind.AUDIO_GENERATION && task.kind() != Task.Kind.IMAGE_GENERATION
                && task.kind() != Task.Kind.VIDEO_GENERATION)
                || (task.status() != Task.Status.CANCELED
                        && task.status() != Task.Status.FAILED
                        && (task.status() != Task.Status.BLOCKED
                                || (!"TASK_INPUT_STALE".equals(task.errorCode())
                                        && !"TASK_PROJECT_ARCHIVED".equals(task.errorCode())
                                        && !"MEDIA_CAPABILITY_CHANGED".equals(task.errorCode())
                                        && !"PROVIDER_UNSUPPORTED_CAPABILITY".equals(
                                                task.errorCode())
                                        && !"PROVIDER_UNSUPPORTED_INPUT".equals(task.errorCode())
                                        && !"MEDIA_CREDENTIAL_UNAVAILABLE".equals(
                                                task.errorCode())
                                        && !"RUNNINGHUB_INPUT_UNAVAILABLE".equals(task.errorCode())
                                        && !"LOCAL_DEPTH_MODEL_UNAVAILABLE".equals(
                                                task.errorCode()))))
                || task.providerRequestId() != null) {
            throw new IllegalArgumentException("Media release requires unsubmitted terminal work");
        }
        String prefix = "media:" + task.id();
        UsageEntry reservation = ledger.findByOperationKey(prefix + ":reserve")
                .orElseThrow(() -> new IllegalStateException(
                        "Direct media Task has no media usage reservation"));
        if (ledger.findByOperationKey(prefix + ":settle").isPresent()) {
            throw new IllegalStateException("A settled media Task cannot release its reservation");
        }
        persist(ownerId, entry(task, UsageEntry.EntryType.RELEASE,
                reservation.costSource(), prefix + ":release"));
    }

    /** 查询用户有权访问的项目账本；金额为空表示未知，不序列化为零。 */
    @Transactional(readOnly = true)
    public List<UsageEntry> listProject(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return ledger.listProject(projectId);
    }

    /** 从已批准任务的固定输入生成数量账目，并核验 Provider 与工作流快照。 */
    private UsageEntry entry(Task task, UsageEntry.EntryType type, String source,
            String operationKey) {
        ObjectNode quantity = mapper.createObjectNode();
        boolean runningHub = "RUNNINGHUB_V2".equals(task.input().path("providerProtocol").asText());
        boolean secondsV2 = task.input().path("schemaVersion").asInt(1) >= 2;
        switch (task.kind()) {
            case AUDIO_GENERATION -> {
                quantity.put("audioCount", 1);
                if (runningHub) quantity.putNull("audioSeconds");
                else quantity.put("audioSeconds", dev.agenvas.artifact.domain.AudioGenerationParameters.MAX_GENERATION_SECONDS);
                quantity.put("imageCount", 0); quantity.put("videoCount", 0);
                quantity.put("videoSeconds", "0"); quantity.put("exportCount", 0);
            }
            case IMAGE_GENERATION -> {
                quantity.put("imageCount", 1);
                quantity.put("videoCount", 0);
                quantity.put("videoSeconds", secondsV2 ? "0" : "0.000");
                quantity.put("exportCount", 0);
            }
            case VIDEO_GENERATION -> {
                String durationText;
                if (secondsV2) {
                    JsonNode seconds = task.input().path("durationSeconds");
                    if (runningHub && seconds.isMissingNode()) durationText = null;
                    else {
                        if (!seconds.isInt() || seconds.intValue() < 1 || seconds.intValue() > (runningHub ? dev.agenvas.provider.domain.MediaAdapterRegistry.RUNNINGHUB_MAX_VIDEO_SECONDS : 30)) {
                            throw new IllegalStateException("Approved video Task lacks pinned duration");
                        }
                        durationText = Integer.toString(seconds.intValue());
                    }
                } else {
                    int durationMs = task.input().path("durationMs").asInt(-1);
                    if (durationMs < 100 || durationMs > 30_000) {
                        throw new IllegalStateException("Approved video Task lacks pinned duration");
                    }
                    durationText = BigDecimal.valueOf(durationMs, 3).toPlainString();
                }
                quantity.put("imageCount", 0);
                quantity.put("videoCount", 1);
                quantity.put("videoSeconds", durationText);
                quantity.put("exportCount", 0);
            }
            default -> throw new IllegalArgumentException("Usage requires a direct media Task");
        }
        quantity.put("llmRequestCount", 0);
        quantity.putNull("inputTokens");
        quantity.putNull("outputTokens");
        int configVersion = task.input().path("providerConfigVersion").asInt(-1);
        String workflowVersion = task.input().path("workflowVersion").asText("");
        if (configVersion < 1 || workflowVersion.isBlank()
                || !("MOCK_UNPRICED".equals(source) || "PROVIDER_UNPRICED".equals(source)
                        || "LOCAL_NO_COST".equals(source) || "ADMIN_CONFIGURED".equals(source))) {
            throw new IllegalStateException("Media usage configuration snapshot is invalid");
        }
        boolean localNoCost = "LOCAL_NO_COST".equals(source);
        BigDecimal knownZero = localNoCost ? BigDecimal.ZERO : null;
        JsonNode pricing = task.input().path("mediaPricing");
        BigDecimal estimate = knownZero;
        String currency = null;
        if (!localNoCost && pricing.isObject()) {
            estimate = new BigDecimal(pricing.path("amount").asText());
            if ("SECOND".equals(pricing.path("unit").asText())) {
                JsonNode duration = quantity.path(task.kind() == Task.Kind.AUDIO_GENERATION ? "audioSeconds" : "videoSeconds");
                estimate = duration.isNull() ? null : estimate.multiply(new BigDecimal(duration.asText()));
            }
            if (estimate != null) estimate = estimate.setScale(MONEY_SCALE);
            currency = pricing.path("currency").asText();
        }
        return new UsageEntry(UUID.randomUUID(), task.projectId(), task.runId(), task.id(),
                operationKey, type, quantity, estimate, knownZero, currency,
                localNoCost ? UsageEntry.CostStatus.KNOWN
                        : estimate != null ? UsageEntry.CostStatus.ESTIMATED : UsageEntry.CostStatus.UNKNOWN,
                estimate != null && !localNoCost ? "ADMIN_CONFIGURED" : source, configVersion,
                workflowVersion, null, clock.instant());
    }

    /** 幂等插入账目；重放时逐字段比较载荷，冲突则拒绝并在首次插入时追加事件。 */
    private void persist(UUID ownerId, UsageEntry entry) {
        if (!ledger.insertOnce(entry)) {
            UsageEntry prior = ledger.findByOperationKey(entry.operationKey()).orElseThrow();
            if (!prior.projectId().equals(entry.projectId())
                    || !prior.taskId().equals(entry.taskId())
                    || !Objects.equals(prior.runId(), entry.runId())
                    || prior.entryType() != entry.entryType()
                    || !prior.quantity().equals(entry.quantity())
                    || !prior.costSource().equals(entry.costSource())
                    || prior.costStatus() != entry.costStatus()
                    || !Objects.equals(prior.estimatedCost(), entry.estimatedCost())
                    || !Objects.equals(prior.actualCost(), entry.actualCost())
                    || !Objects.equals(prior.currency(), entry.currency())
                    || !Objects.equals(prior.providerConfigVersion(),
                            entry.providerConfigVersion())
                    || !Objects.equals(prior.workflowVersion(), entry.workflowVersion())
                    || !Objects.equals(prior.modelId(), entry.modelId())) {
                throw new IllegalStateException("Usage operation key conflicts with another payload");
            }
            return;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("operationKey", entry.operationKey());
        payload.put("entryType", entry.entryType().name());
        payload.put("costStatus", entry.costStatus().name());
        payload.set("quantity", entry.quantity().deepCopy());
        events.append(ownerId, entry.projectId(),
                new ProjectEventService.EventDraft("usage.changed", 1,
                        entry.id(), 1, payload));
    }
}
