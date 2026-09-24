package dev.agenvas.llm.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.export.application.ExportProposal;
import dev.agenvas.export.application.ExportProposalService;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.AgentTurnLeaseGuard;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validates one recorded model call and commits its creative command and ledger atomically. */
@Service
public class ToolExecutionService {

    private static final int MAX_TOOLS_PER_RUN = 40;
    private static final Set<String> CREATE_TEXT_FIELDS = Set.of("title", "text", "format");

    private final AgentRunRepository runs;
    private final LlmTurnRepository turns;
    private final ToolExecutionRepository ledger;
    private final ArtifactService artifacts;
    private final CreativeArtifactToolService creative;
    private final ReadToolService reader;
    private final ExecutionPlanService plans;
    private final ExportProposalService exportProposals;
    private final ProjectEventService events;
    private final AgentTurnLeaseGuard leaseGuard;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ToolExecutionService(AgentRunRepository runs, LlmTurnRepository turns,
            ToolExecutionRepository ledger, ArtifactService artifacts,
            CreativeArtifactToolService creative, ReadToolService reader,
            ExecutionPlanService plans, ExportProposalService exportProposals,
            ProjectEventService events, AgentTurnLeaseGuard leaseGuard,
            ObjectMapper mapper, Clock clock) {
        this.runs = runs;
        this.turns = turns;
        this.ledger = ledger;
        this.artifacts = artifacts;
        this.creative = creative;
        this.reader = reader;
        this.plans = plans;
        this.exportProposals = exportProposals;
        this.events = events;
        this.leaseGuard = leaseGuard;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Replays an existing result or executes the exact call found in a committed model response. */
    @Transactional
    public JsonNode execute(TrustedToolContext context, int stepIndex, String toolCallId) {
        return executeInternal(context, stepIndex, toolCallId, null, null);
    }

    /** Runtime entry point: checks the fenced Task lease under the same business transaction. */
    @Transactional
    public JsonNode executeLeased(TrustedToolContext context, int stepIndex,
            String toolCallId, Task lease, String workerId) {
        if (lease == null || context == null || !lease.projectId().equals(context.projectId())
                || !lease.runId().equals(context.runId())) {
            throw invalid("Agent turn lease and trusted Run scope do not match");
        }
        return executeInternal(context, stepIndex, toolCallId, lease, workerId);
    }

    private JsonNode executeInternal(TrustedToolContext context, int stepIndex,
            String toolCallId, Task lease, String workerId) {
        if (context == null || stepIndex < 0 || toolCallId == null
                || toolCallId.isBlank() || toolCallId.length() > 200) {
            throw invalid("Invalid Run step or tool call ID");
        }
        return events.recordChange(context.ownerId(), context.projectId(), () -> {
            if (lease != null) {
                leaseGuard.requireActive(lease, workerId);
            }
            return ProjectEventService.Change.unchanged(
                    executeLocked(context, stepIndex, toolCallId));
        }).value();
    }

    /** Uses the same project-before-Run lock order as edits, cancellation and approval. */
    private JsonNode executeLocked(TrustedToolContext context, int stepIndex, String toolCallId) {
        AgentRun run = runs.findForUpdate(context.ownerId(), context.projectId(), context.runId())
                .orElseThrow(() -> conflict("Run is not accessible"));
        LlmTurn turn = turns.find(context.projectId(), context.runId(), stepIndex)
                .orElseThrow(() -> conflict("Model turn checkpoint is missing"));
        if (turn.status() != LlmTurn.Status.RESPONDED) {
            throw conflict("Complete model response must be committed before tool execution");
        }
        JsonNode call = findCall(turn.response(), toolCallId);
        String toolName = call.path("name").asText();
        if (run.contextSnapshot().has("redoShotArtifactId")
                && !"propose_generation_plan".equals(toolName)) {
            throw invalid("Scoped redo permits only a target-shot media proposal");
        }
        String arguments = call.path("arguments").asText();
        String argumentHash = sha256(arguments);
        ToolExecution existing = ledger.find(context.projectId(), context.runId(),
                stepIndex, toolCallId).orElse(null);
        if (existing != null) {
            if (!existing.toolName().equals(toolName)
                    || !existing.argumentHash().equals(argumentHash)
                    || existing.status() != ToolExecution.Status.COMPLETED) {
                throw conflict("Tool call ledger conflicts with recorded model response");
            }
            return existing.result();
        }
        if (run.status() != AgentRun.Status.RUNNING) {
            throw conflict("Run is not accepting tool execution");
        }
        if (ledger.countByRun(context.projectId(), context.runId()) >= MAX_TOOLS_PER_RUN) {
            throw conflict("Run tool execution budget is exhausted");
        }
        UUID operationId = UUID.randomUUID();
        if (!ledger.insertExecuting(operationId, context.projectId(), context.runId(),
                stepIndex, toolCallId, toolName, argumentHash, clock.instant())) {
            throw conflict("Tool call was executed concurrently");
        }
        JsonNode result = switch (toolName) {
            case "read_project_summary" -> reader.projectSummary(context, run,
                    operationId, arguments);
            case "read_selection" -> reader.selection(run, operationId, arguments);
            case "read_artifacts" -> reader.artifacts(context, run, operationId, arguments);
            case "read_task_status" -> reader.taskStatus(context, operationId, arguments);
            case "create_text" -> createText(context, run, operationId, arguments);
            case "create_character" -> creative.createCharacter(context, run, operationId, arguments);
            case "create_scene" -> creative.createScene(context, run, operationId, arguments);
            case "create_shots" -> creative.createShots(context, run, operationId, arguments);
            case "revise_artifact" -> creative.reviseArtifact(context, run, operationId, arguments);
            case "place_artifacts" -> creative.placeArtifacts(context, run,
                    operationId, arguments);
            case "arrange_items" -> creative.arrangeItems(context, run,
                    operationId, arguments);
            case "link_artifacts" -> creative.linkArtifacts(context, run,
                    operationId, arguments);
            case "propose_generation_plan" -> proposePlan(context, operationId, arguments);
            case "propose_export" -> proposeExport(context, run, operationId, arguments);
            default -> throw invalid("Tool is not allowlisted for this Runtime");
        };
        if (!ledger.complete(operationId, result, clock.instant())) {
            throw new IllegalStateException("Reserved tool result could not be completed");
        }
        // JSONB normalizes numeric node types and object order. Return that exact durable
        // representation on the first call so later idempotent replays are identical.
        return ledger.find(context.projectId(), context.runId(), stepIndex, toolCallId)
                .filter(saved -> saved.status() == ToolExecution.Status.COMPLETED
                        && saved.result() != null)
                .orElseThrow(() -> new IllegalStateException("Completed tool result is missing"))
                .result();
    }

    private JsonNode proposePlan(TrustedToolContext context, UUID operationId, String arguments) {
        JsonNode input;
        try {
            input = mapper.readTree(arguments);
        } catch (RuntimeException exception) {
            throw invalid("Plan arguments are not valid JSON");
        }
        ExecutionPlan plan = plans.propose(context, input);
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "WAITING_APPROVAL");
        result.put("operationId", operationId.toString());
        result.putArray("createdIds").add(plan.id().toString());
        result.putArray("updatedIds");
        result.putObject("affectedVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("userVisibleSummary", "已提出媒体计划，等待用户审批");
        return result;
    }

    /** Records an export proposal while leaving FFmpeg authorization to the human API. */
    private JsonNode proposeExport(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        JsonNode input;
        try {
            input = mapper.readTree(arguments);
        } catch (RuntimeException exception) {
            throw invalid("Export proposal arguments are not valid JSON");
        }
        ExportProposal proposal = exportProposals.propose(context, run, input);
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("operationId", operationId.toString());
        result.putArray("createdIds").add(proposal.id().toString());
        result.putArray("updatedIds");
        result.putObject("affectedVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("proposalHash", proposal.proposalHash());
        result.put("userVisibleSummary", "已保存导出提案，需用户审批后才会开始导出");
        return result;
    }

    private JsonNode createText(TrustedToolContext context, AgentRun run,
            UUID operationId, String arguments) {
        JsonNode input;
        try {
            input = mapper.readTree(arguments);
        } catch (RuntimeException exception) {
            throw invalid("Tool arguments are not valid JSON");
        }
        if (input == null || !input.isObject()) {
            throw invalid("create_text requires an object");
        }
        for (String field : input.propertyNames()) {
            if (!CREATE_TEXT_FIELDS.contains(field)) {
                throw invalid("create_text has an unknown field");
            }
        }
        String title = requiredText(input, "title", 160);
        String text = requiredText(input, "text", 20_000);
        String format = requiredText(input, "format", 20);
        if (!Set.of("PLAIN_TEXT", "MARKDOWN").contains(format)) {
            throw invalid("create_text format is not allowed");
        }
        ObjectNode content = mapper.createObjectNode();
        content.put("format", format);
        content.put("text", text);
        ArtifactService.ArtifactView created = artifacts.createFromAgent(context.ownerId(),
                context.projectId(), context.runId(), Artifact.Kind.TEXT, title, content);
        if (created.currentVersion().createdByKind() != ArtifactVersion.CreatedByKind.AGENT) {
            throw new IllegalStateException("Agent artifact provenance was not recorded");
        }
        creative.placeOutputs(context, run, java.util.List.of(created));
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("operationId", operationId.toString());
        result.putArray("createdIds").add(created.artifact().id().toString());
        result.putArray("updatedIds");
        result.putObject("affectedVersions")
                .put(created.artifact().id().toString(), created.currentVersion().id().toString());
        result.putObject("artifactVersions")
                .put(created.artifact().id().toString(), created.artifact().version());
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.put("userVisibleSummary", "已创建文本产物");
        return result;
    }

    private JsonNode findCall(JsonNode response, String toolCallId) {
        JsonNode generations = response.path("generations");
        if (!generations.isArray() || generations.isEmpty()) {
            throw conflict("Saved model response lacks generations");
        }
        JsonNode matched = null;
        JsonNode calls = generations.get(0).path("assistant").path("toolCalls");
        if (!calls.isArray()) {
            throw conflict("Selected model generation lacks tool calls");
        }
        for (JsonNode call : calls) {
            if (toolCallId.equals(call.path("id").asText())) {
                if (matched != null || !"function".equals(call.path("type").asText())
                        || !call.path("name").isTextual()
                        || !call.path("arguments").isTextual()) {
                    throw conflict("Saved model tool call is ambiguous or malformed");
                }
                matched = call;
            }
        }
        if (matched == null) {
            throw conflict("Tool call ID was not present in the saved model response");
        }
        return matched;
    }

    private String requiredText(JsonNode input, String field, int maximumLength) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximumLength) {
            throw invalid("create_text has an invalid " + field);
        }
        return value.asText();
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                "工具参数无效", detail, false);
    }

    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "TOOL_EXECUTION_CONFLICT",
                "工具执行冲突", detail, false);
    }
}
