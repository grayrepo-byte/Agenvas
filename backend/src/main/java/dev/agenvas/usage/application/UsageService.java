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

/** Idempotent model, media, and export accounting without inventing prices. */
@Service
public class UsageService {

    private final UsageRepository ledger;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final LlmModeProperties llmMode;

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

    /** A durable REQUESTED checkpoint reserves one bounded model request. */
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

    /** A saved response settles the same request, with tokens only when reported. */
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

    private String modelOperationKey(LlmTurn turn, String stage) {
        return "llm:" + turn.runId() + ":" + turn.stepIndex() + ":" + stage;
    }

    private Integer tokenCount(JsonNode usage, String field) {
        JsonNode value = usage.path(field);
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0
                ? value.asInt() : null;
    }

    private void putNullableToken(ObjectNode quantity, String field, Integer tokens) {
        if (tokens == null) quantity.putNull(field);
        else quantity.put(field, tokens);
    }

    /** Called in the authenticated approval transaction after each media Task is created. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveMediaTask(UUID ownerId, Task task, String costSource) {
        if (task.planId() == null || task.runId() == null) {
            throw new IllegalArgumentException("Approved media Task requires plan and Run IDs");
        }
        persist(ownerId, entry(task, UsageEntry.EntryType.RESERVATION,
                costSource, "media:" + task.id() + ":reserve"));
    }

    /** Called only after the fenced media result is committed in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleMediaTask(UUID ownerId, Task task) {
        if (task.planId() == null) return;
        UsageEntry reservation = ledger.findByOperationKey(
                "media:" + task.id() + ":reserve").orElseThrow(() ->
                new IllegalStateException("Approved Task has no media usage reservation"));
        persist(ownerId, entry(task, UsageEntry.EntryType.SETTLEMENT,
                reservation.costSource(), "media:" + task.id() + ":settle"));
    }

    /** Releases media work whose caller proved no submission checkpoint was reached. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseUnsubmittedMediaTask(UUID ownerId, Task task) {
        if ((task.kind() != Task.Kind.IMAGE_GENERATION
                && task.kind() != Task.Kind.VIDEO_GENERATION)
                || (task.status() != Task.Status.CANCELED
                        && task.status() != Task.Status.FAILED
                        && (task.status() != Task.Status.BLOCKED
                                || (!"TASK_INPUT_STALE".equals(task.errorCode())
                                        && !"TASK_PROJECT_ARCHIVED".equals(task.errorCode()))))
                || task.providerRequestId() != null) {
            throw new IllegalArgumentException("Media release requires unsubmitted terminal work");
        }
        String prefix = "media:" + task.id();
        UsageEntry reservation = ledger.findByOperationKey(prefix + ":reserve")
                .orElseThrow(() -> new IllegalStateException(
                        "Approved Task has no media usage reservation"));
        if (ledger.findByOperationKey(prefix + ":settle").isPresent()) {
            throw new IllegalStateException("A settled media Task cannot release its reservation");
        }
        persist(ownerId, entry(task, UsageEntry.EntryType.RELEASE,
                reservation.costSource(), prefix + ":release"));
    }

    /** A local export reservation records demand, not proof that a video was produced. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveExportTask(UUID ownerId, Task task) {
        if (task.kind() != Task.Kind.MEDIA_EXPORT || task.runId() != null) {
            throw new IllegalArgumentException("Project export usage requires a local export Task");
        }
        persist(ownerId, entry(task, UsageEntry.EntryType.RESERVATION,
                "LOCAL_UNPRICED", "export:" + task.id() + ":reserve"));
    }

    /** Only a fenced SUCCEEDED export gets a completion count. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleExportTask(UUID ownerId, Task task) {
        if (task.kind() != Task.Kind.MEDIA_EXPORT) {
            throw new IllegalArgumentException("Export settlement requires an export Task");
        }
        if (ledger.findByOperationKey("export:" + task.id() + ":reserve").isEmpty()) {
            throw new IllegalStateException("Export Task has no usage reservation");
        }
        persist(ownerId, entry(task, UsageEntry.EntryType.SETTLEMENT,
                "LOCAL_UNPRICED", "export:" + task.id() + ":settle"));
    }

    /** A terminal export without an output releases its reserved completion count once. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseExportTask(UUID ownerId, Task task) {
        if (task.kind() != Task.Kind.MEDIA_EXPORT
                || (task.status() != Task.Status.CANCELED
                        && task.status() != Task.Status.FAILED)) {
            throw new IllegalArgumentException("Export release requires a failed or canceled Task");
        }
        String prefix = "export:" + task.id();
        if (ledger.findByOperationKey(prefix + ":reserve").isEmpty()) {
            throw new IllegalStateException("Export Task has no usage reservation");
        }
        if (ledger.findByOperationKey(prefix + ":settle").isPresent()) {
            throw new IllegalStateException("A settled export cannot release its reservation");
        }
        persist(ownerId, entry(task, UsageEntry.EntryType.RELEASE,
                "LOCAL_UNPRICED", prefix + ":release"));
    }

    /** Owner-scoped history; null money values are serialized as unknown, never zero. */
    @Transactional(readOnly = true)
    public List<UsageEntry> listProject(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return ledger.listProject(projectId);
    }

    private UsageEntry entry(Task task, UsageEntry.EntryType type, String source,
            String operationKey) {
        ObjectNode quantity = mapper.createObjectNode();
        switch (task.kind()) {
            case IMAGE_GENERATION -> {
                quantity.put("imageCount", 1);
                quantity.put("videoCount", 0);
                quantity.put("videoSeconds", "0.000");
                quantity.put("exportCount", 0);
            }
            case VIDEO_GENERATION -> {
                int durationMs = task.input().path("durationMs").asInt(-1);
                if (durationMs < 100 || durationMs > 30_000) {
                    throw new IllegalStateException("Approved video Task lacks pinned duration");
                }
                quantity.put("imageCount", 0);
                quantity.put("videoCount", 1);
                quantity.put("videoSeconds",
                        BigDecimal.valueOf(durationMs, 3).toPlainString());
                quantity.put("exportCount", 0);
            }
            case MEDIA_EXPORT -> {
                quantity.put("imageCount", 0);
                quantity.put("videoCount", 0);
                quantity.put("videoSeconds", "0.000");
                quantity.put("exportCount", 1);
            }
            default -> throw new IllegalArgumentException("Usage requires a media or export Task");
        }
        quantity.put("llmRequestCount", 0);
        quantity.putNull("inputTokens");
        quantity.putNull("outputTokens");
        boolean export = task.kind() == Task.Kind.MEDIA_EXPORT;
        Integer configVersion = export ? null
                : task.input().path("providerConfigVersion").asInt(-1);
        String workflowVersion = export ? null
                : task.input().path("workflowVersion").asText("");
        if (export ? !"LOCAL_UNPRICED".equals(source)
                : configVersion == null || configVersion < 1
                        || workflowVersion == null || workflowVersion.isBlank()
                        || !("MOCK_UNPRICED".equals(source)
                                || "PROVIDER_UNPRICED".equals(source))) {
            throw new IllegalStateException("Media usage configuration snapshot is invalid");
        }
        return new UsageEntry(UUID.randomUUID(), task.projectId(), task.runId(), task.id(),
                operationKey, type, quantity, null, null, null,
                UsageEntry.CostStatus.UNKNOWN, source, configVersion,
                workflowVersion, null, clock.instant());
    }

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
