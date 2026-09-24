package dev.agenvas.export.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Keeps Agent export suggestions separate from authenticated Task authorization. */
@Service
public class ExportProposalService {

    private static final Set<String> FIELDS = Set.of("aspectRatio", "segments");
    private static final Set<String> SEGMENT_FIELDS = Set.of("shotArtifactId",
            "shotVersionId", "videoArtifactId", "videoVersionId", "startMs", "endMs");
    private final ProjectService projects;
    private final AgentRunService runs;
    private final ArtifactService artifacts;
    private final MediaExportService exports;
    private final ExportProposalRepository proposals;
    private final TaskService tasks;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ExportProposalService(ProjectService projects, AgentRunService runs,
            ArtifactService artifacts,
            MediaExportService exports, ExportProposalRepository proposals, TaskService tasks,
            ProjectEventService events, ObjectMapper mapper, Clock clock) {
        this.projects = projects;
        this.runs = runs;
        this.artifacts = artifacts;
        this.exports = exports;
        this.proposals = proposals;
        this.tasks = tasks;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Saves a bounded, Run-scoped suggestion; no media Task or FFmpeg process is created. */
    @Transactional
    public ExportProposal propose(TrustedToolContext context, AgentRun run, JsonNode input) {
        return events.recordChange(context.ownerId(), context.projectId(), () -> {
            ExportProposal proposal = proposeLocked(context, run, input);
            proposals.create(proposal);
            return ProjectEventService.Change.changed(proposal, event(proposal));
        }).value();
    }

    /** Resolves every mutable input after the project event lock is held. */
    private ExportProposal proposeLocked(TrustedToolContext context, AgentRun run,
            JsonNode input) {
        projects.requireActiveProject(context.ownerId(), context.projectId());
        AgentRun currentRun = runs.get(context.ownerId(), context.projectId(), context.runId());
        if (!context.runId().equals(run.id()) || !context.projectId().equals(run.projectId())
                || !context.ownerId().equals(run.userId())
                || currentRun.status() != AgentRun.Status.RUNNING
                || currentRun.contextSnapshot().has("redoShotArtifactId")) {
            throw conflict("Run cannot propose a project export");
        }
        if (input == null || !input.isObject()) {
            throw invalid("Export proposal must be an object");
        }
        requireOnly(input, FIELDS);
        String aspectRatio = text(input, "aspectRatio");
        if (!Set.of("LANDSCAPE_16_9", "PORTRAIT_9_16", "SQUARE_1_1")
                .contains(aspectRatio)) {
            throw invalid("Unsupported export aspect ratio");
        }
        JsonNode supplied = input.path("segments");
        if (!supplied.isArray() || supplied.isEmpty() || supplied.size() > 6) {
            throw invalid("Export proposal needs one to six ordered segments");
        }
        List<MediaExportService.SegmentRequest> segments = new ArrayList<>();
        List<UUID> shotIds = new ArrayList<>();
        List<UUID> shotVersionIds = new ArrayList<>();
        ArrayNode pins = mapper.createArrayNode();
        Set<UUID> seenShots = new HashSet<>();
        for (JsonNode segment : supplied) {
            if (!segment.isObject()) throw invalid("Each export segment must be an object");
            requireOnly(segment, SEGMENT_FIELDS);
            UUID shotId = uuid(segment, "shotArtifactId");
            UUID shotVersionId = uuid(segment, "shotVersionId");
            UUID videoId = uuid(segment, "videoArtifactId");
            UUID videoVersionId = uuid(segment, "videoVersionId");
            if (!seenShots.add(shotId)) throw invalid("Each shot may appear only once");
            shotIds.add(shotId);
            shotVersionIds.add(shotVersionId);
            pin(context, currentRun, shotId, shotVersionId, Artifact.Kind.SHOT, pins);
            pin(context, currentRun, videoId, videoVersionId, Artifact.Kind.VIDEO, pins);
            int startMs = millisecond(segment, "startMs");
            int endMs = millisecond(segment, "endMs");
            segments.add(new MediaExportService.SegmentRequest(videoId, videoVersionId,
                    startMs, endMs));
        }
        MediaExportService.ExportPreview preview;
        try {
            preview = exports.preview(context.ownerId(), context.projectId(), segments);
        } catch (ApiProblemException invalidExport) {
            if ("EXPORT_INPUT_INVALID".equals(invalidExport.code())) {
                throw invalid("Export segment range or media is invalid");
            }
            throw invalidExport;
        }
        if (!aspectRatio.equals(preview.inputSnapshot().path("aspectRatio").asText())) {
            throw invalid("Proposed aspect ratio differs from project settings");
        }
        ObjectNode proposedInput = ((ObjectNode) preview.inputSnapshot()).deepCopy();
        for (int index = 0; index < shotIds.size(); index++) {
            ObjectNode selected = (ObjectNode) proposedInput.path("segments").get(index);
            selected.put("shotArtifactId", shotIds.get(index).toString());
            selected.put("shotVersionId", shotVersionIds.get(index).toString());
        }
        String hash = sha256(proposedInput + "\n" + pins + "\n"
                + preview.projectVersion());
        ExportProposal proposal = new ExportProposal(UUID.randomUUID(), context.projectId(),
                context.runId(), ExportProposal.Status.PENDING, proposedInput, pins,
                hash, preview.projectVersion(), null, null, clock.instant(), null);
        return proposal;
    }

    /** The project owner may inspect persisted proposals even after the Agent Run ends. */
    @Transactional(readOnly = true)
    public List<ExportProposal> list(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return proposals.list(projectId);
    }

    /** Returns one exact proposal after project ownership is verified. */
    @Transactional(readOnly = true)
    public ExportProposal get(UUID ownerId, UUID projectId, UUID proposalId) {
        projects.get(ownerId, projectId);
        return proposals.find(projectId, proposalId).orElseThrow(this::notFound);
    }

    /** Exact-hash human approval creates a single project-level export Task. */
    @Transactional
    public Approval approve(UUID ownerId, UUID projectId, UUID proposalId,
            String displayedHash) {
        if (displayedHash == null || !displayedHash.matches("[0-9a-f]{64}")) {
            throw invalid("A valid displayed proposal hash is required");
        }
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            ExportProposal proposal = proposals.findForUpdate(projectId, proposalId)
                    .orElseThrow(this::notFound);
            if (!proposal.proposalHash().equals(displayedHash)) {
                throw conflict("Export proposal changed; reload before approval");
            }
            if (proposal.status() == ExportProposal.Status.APPROVED) {
                Task existing = tasks.get(ownerId, projectId, proposal.approvedTaskId());
                return ProjectEventService.Change.unchanged(
                        new Approval(proposal, existing, true));
            }
            if (proposal.status() != ExportProposal.Status.PENDING
                    || !inputsCurrent(ownerId, projectId, proposal)) {
                throw conflict("Export proposal is no longer current or pending");
            }
            Task task = tasks.createProjectExport(ownerId, projectId,
                    "agent-export-" + proposal.id(), proposal.input(), proposal.projectVersion());
            if (!proposals.decide(projectId, proposalId, ExportProposal.Status.APPROVED,
                    task.id(), ownerId, clock.instant())) {
                throw conflict("Export proposal changed during approval");
            }
            ExportProposal decided = proposals.find(projectId, proposalId).orElseThrow();
            events.append(ownerId, projectId, event(decided));
            return ProjectEventService.Change.unchanged(new Approval(decided, task, false));
        }).value();
    }

    /** Rejection records a human decision without creating a Task. */
    @Transactional
    public ExportProposal reject(UUID ownerId, UUID projectId, UUID proposalId) {
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            ExportProposal proposal = proposals.findForUpdate(projectId, proposalId)
                    .orElseThrow(this::notFound);
            if (proposal.status() == ExportProposal.Status.REJECTED) {
                return ProjectEventService.Change.unchanged(proposal);
            }
            if (proposal.status() != ExportProposal.Status.PENDING
                    || !proposals.decide(projectId, proposalId,
                            ExportProposal.Status.REJECTED, null, ownerId, clock.instant())) {
                throw conflict("Only a pending export proposal can be rejected");
            }
            ExportProposal decided = proposals.find(projectId, proposalId).orElseThrow();
            return ProjectEventService.Change.changed(decided, event(decided));
        }).value();
    }

    private void pin(TrustedToolContext context, AgentRun run, UUID artifactId,
            UUID versionId, Artifact.Kind kind, ArrayNode pins) {
        ArtifactVersion version = artifacts.requireAgentVisibleVersion(context.ownerId(),
                context.projectId(), context.runId(), versionId, run.contextSnapshot());
        if (!version.artifactId().equals(artifactId)) throw invalid("Version owner mismatch");
        ArtifactService.ArtifactView current = artifacts.get(context.ownerId(),
                context.projectId(), artifactId);
        if (current.artifact().kind() != kind || current.artifact().archivedAt() != null
                || !current.currentVersion().id().equals(versionId)) {
            throw invalid("Export input kind or current version does not match");
        }
        ObjectNode pin = pins.addObject();
        pin.put("artifactId", artifactId.toString());
        pin.put("versionId", versionId.toString());
        pin.put("artifactVersion", current.artifact().version());
    }

    private boolean inputsCurrent(UUID ownerId, UUID projectId, ExportProposal proposal) {
        if (projects.requireActiveProject(ownerId, projectId).version()
                != proposal.projectVersion()) return false;
        for (JsonNode pin : proposal.inputPins()) {
            try {
                ArtifactService.ArtifactView current = artifacts.get(ownerId, projectId,
                        UUID.fromString(pin.path("artifactId").asText()));
                if (current.artifact().archivedAt() != null
                        || current.artifact().version() != pin.path("artifactVersion").longValue()
                        || !current.currentVersion().id().toString()
                                .equals(pin.path("versionId").asText())) return false;
            } catch (ApiProblemException | IllegalArgumentException failure) {
                return false;
            }
        }
        return true;
    }

    private ProjectEventService.EventDraft event(ExportProposal proposal) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("proposalId", proposal.id().toString());
        payload.put("status", proposal.status().name());
        if (proposal.approvedTaskId() != null) {
            payload.put("taskId", proposal.approvedTaskId().toString());
        }
        return new ProjectEventService.EventDraft("export.proposal.changed", 1,
                proposal.id(), proposal.status() == ExportProposal.Status.PENDING ? 0 : 1,
                payload);
    }

    private void requireOnly(JsonNode value, Set<String> allowed) {
        for (String name : value.propertyNames()) {
            if (!allowed.contains(name)) throw invalid("Unknown export proposal field");
        }
    }

    private String text(JsonNode value, String field) {
        JsonNode selected = value.path(field);
        if (!selected.isTextual() || selected.asText().isBlank()) {
            throw invalid("Missing or invalid " + field);
        }
        return selected.asText();
    }

    private UUID uuid(JsonNode value, String field) {
        try {
            return UUID.fromString(text(value, field));
        } catch (IllegalArgumentException failure) {
            throw invalid("Invalid " + field);
        }
    }

    private int millisecond(JsonNode value, String field) {
        JsonNode selected = value.path(field);
        if (!selected.isIntegralNumber() || !selected.canConvertToInt()) {
            throw invalid("Invalid " + field);
        }
        return selected.intValue();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                "导出提案无效", detail, false);
    }

    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "EXPORT_PROPOSAL_CONFLICT",
                "导出提案已变化", detail, false);
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "导出提案不存在", "该项目没有此导出提案。", false);
    }

    /** Human decision with the already-existing or newly created persistent Task. */
    public record Approval(ExportProposal proposal, Task task, boolean replayed) {}
}
