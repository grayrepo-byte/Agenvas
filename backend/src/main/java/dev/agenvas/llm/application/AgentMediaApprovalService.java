package dev.agenvas.llm.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.domain.AgentMediaApproval;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Proposals freeze validated media inputs; only an authenticated batch approval creates Tasks. */
@Service
public class AgentMediaApprovalService {
    public static final Duration APPROVAL_LIFETIME = Duration.ofHours(24);
    public static final Duration EXECUTION_LIFETIME = Duration.ofHours(24);
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_OUTPUTS = 6;
    private static final int OUTPUTS_PER_REQUEST = 1;
    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_PROMPT_LENGTH = 20_000;
    private static final int MAX_DECISION_KEY_LENGTH = 200;
    private static final int MAX_TOOL_CALL_ID_LENGTH = 200;
    private static final String INPUT_COLOR = "#7C3AED";
    private static final Set<String> OUTPUT_FIELDS = Set.of("kind", "title", "prompt",
            "capabilityId", "parameters", "durationSeconds", "videoInputMode", "mediaInputs");

    private final AgentMediaApprovalRepository approvals;
    private final AgentRunService runs;
    private final ArtifactService artifacts;
    private final CanvasService canvas;
    private final CanvasConnectionService connections;
    private final MediaDraftService drafts;
    private final MediaCapabilityService capabilities;
    private final DirectMediaTaskService mediaTasks;
    private final ProjectEventService events;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ToolExecutionRepository toolLedger;

    public AgentMediaApprovalService(AgentMediaApprovalRepository approvals, AgentRunService runs,
            ArtifactService artifacts, CanvasService canvas, MediaDraftService drafts,
            MediaCapabilityService capabilities, DirectMediaTaskService mediaTasks,
            ProjectEventService events, ApplicationEventPublisher publisher,
            ObjectMapper mapper, Clock clock, ToolExecutionRepository toolLedger,
            CanvasConnectionService connections) {
        this.approvals = approvals;
        this.runs = runs;
        this.artifacts = artifacts;
        this.canvas = canvas;
        this.connections = connections;
        this.drafts = drafts;
        this.capabilities = capabilities;
        this.mediaTasks = mediaTasks;
        this.events = events;
        this.publisher = publisher;
        this.mapper = mapper;
        this.clock = clock;
        this.toolLedger = toolLedger;
    }

    /** Tool execution and this proposal share the existing project transaction and call ledger. */
    @Transactional
    public JsonNode propose(TrustedToolContext context, AgentRun run, UUID operationId,
            int stepIndex, String toolCallId, String arguments) {
        if (!run.id().equals(context.runId()) || !run.projectId().equals(context.projectId())
                || !run.userId().equals(context.ownerId()) || stepIndex < 0
                || stepIndex >= AgentRun.MAX_MODEL_TURNS - 1 || operationId == null
                || toolCallId == null || toolCallId.isBlank()
                || toolCallId.length() > MAX_TOOL_CALL_ID_LENGTH) {
            throw invalid("proposal-scope");
        }
        ObjectNode input = object(arguments);
        allowOnly(input, Set.of("outputs"));
        JsonNode outputs = input.path("outputs");
        if (!outputs.isArray() || outputs.isEmpty() || outputs.size() > MAX_OUTPUTS) {
            throw invalid("outputs");
        }
        try {
            return events.recordChange(context.ownerId(), context.projectId(), () -> {
                AgentMediaApproval prior = approvals.findByToolCall(context.projectId(),
                        context.runId(), stepIndex, toolCallId).orElse(null);
                if (prior != null) {
                    if (!prior.request().path("argumentHash").asText().equals(Sha256.hex(arguments))) {
                        throw conflict("IDEMPOTENCY_KEY_CONFLICT", "proposal-key");
                    }
                    return ProjectEventService.Change.unchanged(toolResult(prior));
                }
                AgentRun current = runs.get(context.ownerId(), context.projectId(), context.runId());
                if (current.status() != AgentRun.Status.RUNNING || current.nextStepIndex() != stepIndex) {
                    throw conflict("AGENT_MEDIA_APPROVAL_CONFLICT", "run-unavailable");
                }
                List<OutputRequest> requests = new ArrayList<>();
                for (JsonNode output : outputs) requests.add(parseOutput(context, run, output));
                List<ArtifactService.ArtifactView> created = new ArrayList<>();
                for (OutputRequest request : requests) {
                    // An empty media identity has no content version or external side effect yet.
                    created.add(artifacts.create(context.ownerId(), context.projectId(),
                            request.kind(), request.title(), null));
                }
                var placed = canvas.placeArtifactsInAgentOutputWithinChange(context.ownerId(),
                        context.projectId(), run.agentInstanceId(),
                        created.stream().map(view -> view.artifact().id()).toList());
                ObjectNode frozenRequest = mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION);
                frozenRequest.put("argumentHash", Sha256.hex(arguments));
                JsonNode skillSource = freezeSkillSource(run);
                if (skillSource != null) frozenRequest.set("creativeSkill", skillSource);
                var normalizedOutputs = frozenRequest.putArray("outputs");
                ObjectNode targets = mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION);
                var targetOutputs = targets.putArray("outputs");
                for (int index = 0; index < requests.size(); index++) {
                    OutputRequest request = requests.get(index);
                    UUID artifactId = created.get(index).artifact().id();
                    CanvasItem item = placed.created().stream()
                            .filter(card -> card.subjectId().equals(artifactId)).findFirst().orElseThrow();
                    Task.Kind taskKind = Task.Kind.valueOf(request.kind().name() + "_GENERATION");
                    UUID capabilityId = capabilities.forDraft(request.capabilityId(), taskKind).capabilityId();
                    MediaDraft initial = drafts.get(context.ownerId(), context.projectId(), item.id());
                    MediaDraft saved = drafts.save(context.ownerId(), context.projectId(), item.id(),
                            initial.version(), request.prompt(), request.parameters(),
                            request.durationSeconds(), capabilityId, request.videoInputMode(),
                            request.mediaInputs(), List.of(), null);
                    for (MediaDraft.MediaInput reference : saved.mediaInputs()) {
                        CanvasItem source = canvas.ensureMediaReferenceWithinChange(context.ownerId(),
                                context.projectId(), run.agentInstanceId(), reference.artifactId(), reference.versionId());
                        saved = connections.connectPreparedMediaInputWithinChange(context.ownerId(),
                                context.projectId(), source.id(), item.id(), reference.versionId(), saved.version()).draft();
                    }
                    var preflight = approvedPreflight(context.ownerId(), context.projectId(),
                            artifactId, item.id(), saved.version(), skillSource);
                    if (preflight.outputCount() != OUTPUTS_PER_REQUEST) throw invalid("single-output");
                    ObjectNode normalized = request.original().deepCopy();
                    normalized.put("capabilityId", capabilityId.toString());
                    normalizedOutputs.add(normalized);
                    ObjectNode target = targetOutputs.addObject();
                    target.put("artifactId", artifactId.toString());
                    target.put("canvasItemId", item.id().toString());
                    target.put("draftVersion", saved.version());
                    target.put("frozenInputHash", preflight.frozenInputHash());
                    target.set("binding", mapper.valueToTree(preflight.binding()));
                    target.set("preview", preflight.safeSummary().deepCopy());
                }
                Instant now = clock.instant();
                AgentMediaApproval approval = new AgentMediaApproval(UUID.randomUUID(), context.ownerId(),
                        context.projectId(), context.runId(), stepIndex, toolCallId, operationId,
                        frozenRequest, targets, List.of(), null, AgentMediaApproval.Status.PENDING,
                        0, now, now.plus(APPROVAL_LIFETIME), null, null, null);
                if (!approvals.insert(approval)) throw conflict("AGENT_MEDIA_APPROVAL_CONFLICT", "changed");
                appendEvent(context.ownerId(), approval);
                return ProjectEventService.Change.unchanged(toolResult(approval));
            }).value();
        } catch (ApiProblemException problem) {
            // Reused draft/preflight validators retain their safe explanation; the Runtime
            // recognizes tool argument failures and can repair the saved model response.
            // RunningHub/ComfyUI input validators use 422, while native media
            // validators use 400. Both reject the proposed arguments before
            // approval or Provider dispatch and must enter the same bounded repair.
            if ((problem.status() == HttpStatus.BAD_REQUEST
                    || problem.status() == HttpStatus.UNPROCESSABLE_ENTITY)
                    && !"TOOL_ARGUMENT_INVALID".equals(problem.code())) {
                throw new ApiProblemException(problem.status(), "TOOL_ARGUMENT_INVALID",
                        problem.title(), problem.detail(), false);
            }
            throw problem;
        }
    }

    @Transactional(readOnly = true)
    public List<ApprovalView> list(UUID ownerId, UUID projectId, UUID runId) {
        runs.get(ownerId, projectId, runId);
        return approvals.listByRun(projectId, runId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ApprovalView get(UUID ownerId, UUID projectId, UUID runId, UUID approvalId) {
        runs.get(ownerId, projectId, runId);
        return view(approvals.find(projectId, runId, approvalId).orElseThrow(this::notFound));
    }

    /** Every frozen target is rechecked before any Task is inserted; failed checks roll back all. */
    @Transactional
    public ApprovalView decide(UUID ownerId, UUID projectId, UUID runId, UUID approvalId,
            long expectedVersion, Decision decision, String idempotencyKey) {
        if (expectedVersion < 0 || decision == null || idempotencyKey == null
                || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_DECISION_KEY_LENGTH) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "AGENT_MEDIA_ARGUMENT_INVALID",
                    ApiMessage.of("api.agent-media-approval.invalid-title"),
                    ApiMessage.of("api.agent-media-approval.invalid-detail", "decision"), false);
        }
        String hash = Sha256.hex(decision.name() + ":" + expectedVersion);
        return events.recordChange(ownerId, projectId, () -> {
            AgentRun run = runs.get(ownerId, projectId, runId);
            AgentMediaApproval approval = approvals.findForUpdate(projectId, runId, approvalId)
                    .orElseThrow(this::notFound);
            if (idempotencyKey.equals(approval.decisionKey())) {
                if (!hash.equals(approval.decisionHash())) {
                    throw conflict("IDEMPOTENCY_KEY_CONFLICT", "decision-key");
                }
                return ProjectEventService.Change.unchanged(view(approval));
            }
            if (approval.status() != AgentMediaApproval.Status.PENDING
                    || approval.version() != expectedVersion) {
                throw conflict("AGENT_MEDIA_APPROVAL_CONFLICT", "changed");
            }
            if ((run.status() != AgentRun.Status.RUNNING && run.status() != AgentRun.Status.WAITING_TASKS)
                    || (run.nextStepIndex() != approval.stepIndex()
                        && run.nextStepIndex() != approval.stepIndex() + 1)) {
                throw conflict("AGENT_MEDIA_APPROVAL_CONFLICT", "run-unavailable");
            }
            Instant now = clock.instant();
            AgentMediaApproval update;
            if (!now.isBefore(approval.expiresAt())) {
                update = approval.transition(AgentMediaApproval.Status.EXPIRED, List.of(),
                        terminalResult("EXPIRED", "AGENT_MEDIA_APPROVAL_EXPIRED"), null,
                        idempotencyKey, hash);
            } else if (decision == Decision.REJECT) {
                update = approval.transition(AgentMediaApproval.Status.REJECTED, List.of(),
                        terminalResult("REJECTED", "AGENT_MEDIA_APPROVAL_REJECTED"), null,
                        idempotencyKey, hash);
            } else {
                // Shared catalog locks pin administrator-controlled capability and connection
                // versions through both revalidation and the atomic batch Task acceptance.
                for (JsonNode target : approval.targets().path("outputs")) {
                    MediaCapabilityBinding frozenBinding = mapper.treeToValue(target.path("binding"),
                            MediaCapabilityBinding.class);
                    mediaTasks.lockApprovalStyle(ownerId, projectId, uuid(target.path("canvasItemId")));
                    if (!mediaTasks.lockApprovalBinding(frozenBinding)) {
                        throw conflict("AGENT_MEDIA_APPROVAL_STALE", "stale");
                    }
                }
                for (JsonNode target : approval.targets().path("outputs")) {
                    var preflight = approvedPreflight(ownerId, projectId,
                            uuid(target.path("artifactId")), uuid(target.path("canvasItemId")),
                            target.path("draftVersion").longValue(), approval.request().get("creativeSkill"));
                    MediaCapabilityBinding frozenBinding = mapper.treeToValue(target.path("binding"),
                            MediaCapabilityBinding.class);
                    if (preflight.outputCount() != OUTPUTS_PER_REQUEST || !frozenBinding.equals(preflight.binding())
                            || !target.path("frozenInputHash").asText().equals(preflight.frozenInputHash())) {
                        throw conflict("AGENT_MEDIA_APPROVAL_STALE", "stale");
                    }
                }
                List<UUID> taskIds = new ArrayList<>();
                int index = 0;
                for (JsonNode target : approval.targets().path("outputs")) {
                    Task task = approvedTask(ownerId, projectId, runId, approvalId,
                            uuid(target.path("artifactId")), uuid(target.path("canvasItemId")),
                            target.path("draftVersion").longValue(),
                            "agent-media:" + approvalId + ":" + index++, approval.request().get("creativeSkill"));
                    taskIds.add(task.id());
                }
                update = approval.transition(AgentMediaApproval.Status.APPROVED, taskIds,
                        null, now.plus(EXECUTION_LIFETIME), idempotencyKey, hash);
            }
            if (!approvals.update(update, approval.version())) {
                throw conflict("AGENT_MEDIA_APPROVAL_CONFLICT", "changed");
            }
            if (update.status() == AgentMediaApproval.Status.REJECTED) {
                // Only this proposal's untouched placeholders are withdrawn. Direct generation
                // is a separate user action, so its destination survives even before a result exists.
                for (JsonNode target : approval.targets().path("outputs")) {
                    UUID artifactId = uuid(target.path("artifactId"));
                    UUID itemId = uuid(target.path("canvasItemId"));
                    if (canvas.hasArtifactItem(ownerId, projectId, itemId, artifactId)
                            && mediaTasks.list(ownerId, projectId, artifactId, itemId).isEmpty()) {
                        canvas.removeUnchangedEmptyMediaItemWithinChange(ownerId, projectId,
                                itemId, artifactId, target.path("draftVersion").longValue());
                    }
                }
            }
            appendEvent(ownerId, update);
            publisher.publishEvent(new AgentMediaApprovalChanged(ownerId, projectId, runId, approvalId));
            return ProjectEventService.Change.unchanged(view(update));
        }).value();
    }

    private OutputRequest parseOutput(TrustedToolContext context, AgentRun run, JsonNode output) {
        if (!(output instanceof ObjectNode object)) throw invalid("outputs");
        allowOnly(object, OUTPUT_FIELDS);
        Artifact.Kind kind;
        try { kind = Artifact.Kind.valueOf(text(object, "kind", MAX_TITLE_LENGTH, false)); }
        catch (IllegalArgumentException failure) { throw invalid("kind"); }
        if (!Set.of(Artifact.Kind.IMAGE, Artifact.Kind.VIDEO, Artifact.Kind.AUDIO).contains(kind)) {
            throw invalid("kind");
        }
        String title = text(object, "title", MAX_TITLE_LENGTH, false).strip();
        String prompt = text(object, "prompt", MAX_PROMPT_LENGTH, true);
        JsonNode parameters = object.has("parameters") ? object.path("parameters") : mapper.createObjectNode();
        if (!parameters.isObject()) throw invalid("parameters");
        UUID capabilityId = object.hasNonNull("capabilityId") ? uuid(object.path("capabilityId")) : null;
        Integer duration = null;
        if (object.hasNonNull("durationSeconds")) {
            JsonNode value = object.path("durationSeconds");
            if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid("durationSeconds");
            duration = value.intValue();
        }
        MediaDraft.VideoInputMode mode = null;
        if (object.has("videoInputMode")) {
            if (kind != Artifact.Kind.VIDEO) throw invalid("videoInputMode");
            try {
                mode = MediaDraft.VideoInputMode.valueOf(
                        text(object, "videoInputMode", MAX_TITLE_LENGTH, false));
            } catch (IllegalArgumentException failure) {
                throw invalid("videoInputMode");
            }
        }
        List<MediaDraftService.SaveMediaInput> inputs = new ArrayList<>();
        if (object.has("mediaInputs")) {
            if (!object.path("mediaInputs").isArray()) throw invalid("mediaInputs");
            for (JsonNode input : object.path("mediaInputs")) {
                if (!(input instanceof ObjectNode reference)) throw invalid("mediaInputs");
                allowOnly(reference, Set.of("versionId", "role"));
                UUID versionId = uuid(reference.path("versionId"));
                MediaDraft.InputRole role;
                try { role = MediaDraft.InputRole.valueOf(text(reference, "role", MAX_TITLE_LENGTH, false)); }
                catch (IllegalArgumentException failure) { throw invalid("role"); }
                // Model-selected IDs never widen the Run's explicit exact-version context.
                artifacts.requireAgentVisibleVersion(context.ownerId(), context.projectId(),
                        context.runId(), versionId, run.contextSnapshot());
                inputs.add(new MediaDraftService.SaveMediaInput(versionId, role, INPUT_COLOR));
            }
        }
        var activeSkills = RunSkills.activated(run, toolLedger.skillReads(run.projectId(), run.id()));
        // GUIDE restrictions apply across selected Skills, even before activation.
        for (JsonNode selected : RunSkills.available(run)) for (JsonNode reference : selected.path("assets")) {
            boolean present = inputs.stream().anyMatch(media -> media.versionId().toString().equals(reference.path("artifactVersionId").asText()));
            boolean activated = activeSkills.stream().anyMatch(skill -> skill.path("skillVersionId").equals(selected.path("skillVersionId")));
            if (present && ("GUIDE".equals(reference.path("usage").asText()) || !activated)) throw invalid("skill-reference-input");
        }
        if (!activeSkills.isEmpty() && activeSkills.stream().noneMatch(skill ->
                java.util.stream.StreamSupport.stream(skill.path("outputKinds").spliterator(), false)
                        .anyMatch(allowed -> kind.name().equals(allowed.asText())))) throw invalid("skill-output-kind");
        for (JsonNode skill : activeSkills) {
            boolean outputAllowed = false;
            for (JsonNode allowed : skill.path("outputKinds")) if (kind.name().equals(allowed.asText())) outputAllowed = true;
            if (!outputAllowed) {
                if (run.policySnapshot().path("toolPolicyVersion").asInt(1) < RunToolPolicy.PROGRESSIVE_VERSION) throw invalid("skill-output-kind");
                continue; // A combined catalogue can contain Skills for different output kinds.
            }
            for (JsonNode reference : skill.path("assets")) {
                // New fixed Skill images are LLM context, not Provider inputs; user input slots below still apply.
                if (dev.agenvas.skill.domain.SkillContent.AssetDelivery.LLM_CONTEXT.name().equals(skill.path("assetDelivery").asText())) continue;
                boolean present = inputs.stream().anyMatch(media -> media.versionId().toString().equals(reference.path("artifactVersionId").asText()));
                if (("GUIDE".equals(reference.path("usage").asText()) && present)
                        || ("PROVIDER_REFERENCE".equals(reference.path("usage").asText()) && reference.path("required").asBoolean() && !present)) {
                    throw invalid("skill-reference-input");
                }
            }
            for (JsonNode required : skill.path("inputs")) {
                if (required.path("required").asBoolean() && "IMAGE".equals(required.path("kind").asText())
                        && inputs.stream().noneMatch(media -> media.versionId().toString().equals(required.path("artifactVersionId").asText()))) {
                    throw invalid("skill-subject-input");
                }
            }
        }
        return new OutputRequest(kind, title, prompt, parameters, duration, capabilityId, mode,
                List.copyOf(inputs), object);
    }

    private dev.agenvas.task.application.DirectMediaTaskService.MediaPreflight approvedPreflight(UUID owner, UUID project,
            UUID artifact, UUID card, long draftVersion, JsonNode skill) {
        return skill == null ? mediaTasks.preflight(owner, project, artifact, card, draftVersion)
                : mediaTasks.preflightApproved(owner, project, artifact, card, draftVersion, skill);
    }
    private Task approvedTask(UUID owner, UUID project, UUID run, UUID approval, UUID artifact, UUID card,
            long draftVersion, String key, JsonNode skill) {
        return skill == null ? mediaTasks.runApproved(owner, project, run, approval, artifact, card, draftVersion, key)
                : mediaTasks.runApproved(owner, project, run, approval, artifact, card, draftVersion, key, skill);
    }

    private JsonNode freezeSkillSource(AgentRun run) {
        var reads = toolLedger.skillReads(run.projectId(), run.id());
        var active = RunSkills.activated(run, reads);
        if (active.isEmpty()) return null;
        var sources = mapper.createArrayNode();
        for (JsonNode source : active) {
            ObjectNode frozen = (ObjectNode) source.deepCopy();
            Set<String> used = new java.util.HashSet<>();
            var ranges = frozen.putArray("resourceReads");
            var imageReads = dev.agenvas.skill.domain.SkillContent.AssetDelivery.LLM_CONTEXT.name().equals(source.path("assetDelivery").asText())
                    ? frozen.putArray("assetReads") : null;
            for (JsonNode read : reads) {
                boolean sameVersion = source.path("skillVersionId").asText().equals(read.path("skillVersionId").asText());
                if (!sameVersion || "SKILL.md".equals(read.path("path").asText())) continue;
                if (read.path("alias").isTextual()) {
                    if (imageReads != null) imageReads.addObject().put("alias", read.path("alias").asText())
                            .put("contentHash", read.path("contentHash").asText());
                    continue;
                }
                if (!read.path("path").isTextual()) continue;
                used.add(read.path("path").asText());
                ranges.addObject().put("path", read.path("path").asText())
                        .put("contentHash", read.path("contentHash").asText())
                        .put("offset", read.path("offset").asInt()).put("endOffset", read.path("endOffset").asInt()).put("total", read.path("total").asInt());
            }
            var resources = frozen.putArray("resources");
            for (JsonNode resource : source.path("resources")) if (used.contains(resource.path("path").asText())) resources.add(resource.deepCopy());
            sources.add(frozen);
        }
        // Preserve the persisted single-Skill representation for historical Run recovery.
        if (run.policySnapshot().path("toolPolicyVersion").asInt(1) < RunToolPolicy.PROGRESSIVE_VERSION) return sources.get(0);
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", 2);
        result.set("skills", sources);
        return result;
    }

    private ObjectNode object(String arguments) {
        try {
            if (mapper.readTree(arguments) instanceof ObjectNode object) return object;
        } catch (RuntimeException failure) { throw invalid("json"); }
        throw invalid("json");
    }

    private void allowOnly(ObjectNode input, Set<String> fields) {
        for (String name : input.propertyNames()) if (!fields.contains(name)) throw invalid("fields");
    }

    private String text(JsonNode object, String field, int maxLength, boolean allowBlank) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.asText().length() > maxLength
                || !allowBlank && value.asText().isBlank()) throw invalid(field);
        return value.asText();
    }

    private UUID uuid(JsonNode value) {
        if (!value.isTextual()) throw invalid("uuid");
        try { return UUID.fromString(value.asText()); }
        catch (IllegalArgumentException failure) { throw invalid("uuid"); }
    }

    private void appendEvent(UUID ownerId, AgentMediaApproval approval) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("runId", approval.runId().toString());
        payload.put("approvalId", approval.id().toString());
        payload.put("status", approval.status().name());
        events.append(ownerId, approval.projectId(), new ProjectEventService.EventDraft(
                "agent.media.approval.changed", SCHEMA_VERSION, approval.id(), approval.version(), payload));
    }

    private JsonNode terminalResult(String status, String errorCode) {
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION)
                .put("status", status).put("errorCode", errorCode);
        result.putArray("tasks");
        return result;
    }

    private JsonNode toolResult(AgentMediaApproval approval) {
        ObjectNode result = mapper.createObjectNode().put("status", ToolResultStatus.SUCCEEDED.name())
                .put("operationId", approval.operationId().toString())
                .put("approvalId", approval.id().toString()).put("awaitingMedia", true)
                .put("userVisibleSummary", "媒体生成批次已提出，等待用户统一审批；尚未提交第三方");
        var created = result.putArray("createdIds");
        for (JsonNode target : approval.targets().path("outputs")) {
            created.add(target.path("artifactId").asText());
            created.add(target.path("canvasItemId").asText());
        }
        result.putArray("updatedIds");
        result.putObject("affectedVersions");
        result.putArray("taskIds");
        result.putNull("errorCode");
        result.set("data", AgentMediaToolResult.approvalData(approval));
        return result;
    }

    private ApprovalView view(AgentMediaApproval approval) {
        List<ApprovalOutput> outputs = new ArrayList<>();
        JsonNode requests = approval.request().path("outputs");
        JsonNode targets = approval.targets().path("outputs");
        for (int index = 0; index < requests.size(); index++) {
            JsonNode request = requests.get(index);
            JsonNode target = targets.get(index);
            outputs.add(new ApprovalOutput(Artifact.Kind.valueOf(request.path("kind").asText()),
                    request.path("title").asText(), uuid(target.path("artifactId")),
                    uuid(target.path("canvasItemId")), target.path("draftVersion").longValue(),
                    target.path("preview").deepCopy()));
        }
        return new ApprovalView(approval.id(), approval.projectId(), approval.runId(),
                approval.operationId(), approval.status(), approval.version(), List.copyOf(outputs),
                approval.taskIds(), approval.result(), approval.createdAt(), approval.expiresAt(),
                approval.executionDeadline());
    }

    private ApiProblemException invalid(String field) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                ApiMessage.of("api.agent-media-approval.invalid-title"),
                ApiMessage.of("api.agent-media-approval.invalid-detail", field), false);
    }

    private ApiProblemException conflict(String code, String reason) {
        return new ApiProblemException(HttpStatus.CONFLICT, code,
                ApiMessage.of("api.agent-media-approval.conflict-title"),
                ApiMessage.of("api.agent-media-approval.conflict-detail", reason), false);
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                ApiMessage.of("api.agent-media-approval.not-found-title"),
                ApiMessage.of("api.agent-media-approval.not-found-detail"), false);
    }

    private record OutputRequest(Artifact.Kind kind, String title, String prompt,
            JsonNode parameters, Integer durationSeconds, UUID capabilityId,
            MediaDraft.VideoInputMode videoInputMode,
            List<MediaDraftService.SaveMediaInput> mediaInputs, ObjectNode original) {}

    public enum Decision { APPROVE, REJECT }
    public record ApprovalOutput(Artifact.Kind kind, String title, UUID artifactId,
            UUID canvasItemId, long draftVersion, JsonNode preview) {}
    public record ApprovalView(UUID id, UUID projectId, UUID runId, UUID operationId,
            AgentMediaApproval.Status status, long version, List<ApprovalOutput> outputs,
            List<UUID> taskIds, JsonNode result, Instant createdAt, Instant expiresAt,
            Instant executionDeadline) {}
}
