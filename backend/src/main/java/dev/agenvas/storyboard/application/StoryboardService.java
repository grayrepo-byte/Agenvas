package dev.agenvas.storyboard.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.AgentMediaApprovalService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.storyboard.domain.Storyboard;
import dev.agenvas.storyboard.domain.Storyboard.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Project-scoped creative decisions and review provenance; this service never submits media work. */
@Service
public class StoryboardService {
    private static final int MAX_SHOTS = 200;
    private static final int MAX_REFERENCES = 30;
    private static final int HISTORY_PAGE = 100;
    private final StoryboardRepository boards;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final ArtifactService artifacts;
    private final CanvasItemQueryService items;
    private final CanvasService canvas;
    private final AgentMediaApprovalService approvals;
    private final ObjectMapper mapper;

    public StoryboardService(StoryboardRepository boards, ProjectService projects,
            ProjectEventService events, ArtifactService artifacts, CanvasItemQueryService items,
            CanvasService canvas, AgentMediaApprovalService approvals, ObjectMapper mapper) {
        this.boards = boards; this.projects = projects; this.events = events;
        this.artifacts = artifacts; this.items = items; this.canvas = canvas;
        this.approvals = approvals; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Storyboard get(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return boards.current(projectId).orElseGet(() -> Storyboard.empty(projectId));
    }

    @Transactional(readOnly = true)
    public List<Storyboard> revisions(UUID ownerId, UUID projectId, long afterVersion, int limit) {
        projects.get(ownerId, projectId);
        if (afterVersion < 0 || limit < 1 || limit > HISTORY_PAGE) throw invalid("history");
        return boards.revisions(projectId, afterVersion, limit);
    }

    /** Export-only complete history; the public history endpoint remains bounded. */
    @Transactional(readOnly = true)
    public List<Storyboard> listRevisions(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        List<Storyboard> result = new ArrayList<>();
        long after = 0;
        while (true) {
            var page = boards.revisions(projectId, after, HISTORY_PAGE);
            result.addAll(page);
            if (page.size() < HISTORY_PAGE) return List.copyOf(result);
            after = page.getLast().version();
        }
    }

    @Transactional(readOnly = true)
    public List<AgentMediaApprovalService.ApprovalView> mediaApprovals(UUID ownerId, UUID projectId) {
        Storyboard board = get(ownerId, projectId);
        Set<UUID> targets = new HashSet<>();
        board.shots().forEach(shot -> shot.productions().forEach(production -> targets.add(production.canvasItemId())));
        if (board.composition() != null) targets.add(board.composition().canvasItemId());
        return approvals.listForStoryboard(ownerId, projectId, targets, board.shots().stream().map(Shot::id).collect(java.util.stream.Collectors.toSet()));
    }

    @Transactional
    public Storyboard save(UUID ownerId, UUID projectId, Save request) {
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            Storyboard prior = boards.current(projectId).orElseGet(() -> Storyboard.empty(projectId));
            if (request == null || request.expectedVersion() < 0) throw invalid("expectedVersion");
            if (prior.version() != request.expectedVersion()) throw conflict();
            Storyboard next = revise(ownerId, projectId, prior, request);
            if (!boards.append(next, prior.version())) throw conflict();
            var payload = mapper.createObjectNode().put("projectId", projectId.toString()).put("version", next.version());
            return ProjectEventService.Change.changed(next, new ProjectEventService.EventDraft(
                    "storyboard.changed", 1, projectId, next.version(), payload));
        }).value();
    }

    private Storyboard revise(UUID ownerId, UUID projectId, Storyboard prior, Save request) {
        if (request.shots() == null || request.shots().size() > MAX_SHOTS || request.shots().stream().anyMatch(Objects::isNull)) throw invalid("shots");
        Map<UUID, Shot> oldShots = new HashMap<>();
        prior.shots().forEach(shot -> oldShots.put(shot.id(), shot));
        Set<UUID> reintroduced = request.shots().stream().filter(Objects::nonNull).map(ShotInput::id)
                .filter(Objects::nonNull).filter(id -> !oldShots.containsKey(id)).collect(java.util.stream.Collectors.toSet());
        Map<UUID, Integer> historicalRevisions = boards.maxShotRevisions(projectId, reintroduced);
        Set<UUID> ids = new HashSet<>();
        Set<String> codes = new HashSet<>();
        List<Shot> shots = new ArrayList<>();
        boolean compositionChanged = !prior.shots().stream().map(Shot::id).toList()
                .equals(request.shots().stream().map(ShotInput::id).toList());
        for (ShotInput input : request.shots()) {
            if (input == null || input.id() == null || !ids.add(input.id())) throw invalid("shot.id");
            validateInput(ownerId, projectId, input);
            if (input.code().isBlank() || !input.code().equals(input.code().trim()) || !codes.add(input.code())) throw invalid("code");
            Shot old = oldShots.get(input.id());
            boolean creativeChanged = old == null || creativeChanged(old, input);
            int revision = old == null ? Math.addExact(historicalRevisions.getOrDefault(input.id(), 0), 1) : creativeChanged ? Math.addExact(old.revision(), 1) : old.revision();
            List<Production> productions = new ArrayList<>();
            Set<Role> roles = new HashSet<>();
            for (ProductionInput target : input.productions()) {
                if (target == null || target.role() == null || target.canvasItemId() == null
                        || !roles.add(target.role())) throw invalid("production");
                Production oldTarget = old == null ? null : old.productions().stream()
                        .filter(value -> value.role() == target.role()).findFirst().orElse(null);
                boolean sameTarget = oldTarget != null && oldTarget.canvasItemId().equals(target.canvasItemId())
                        && Objects.equals(oldTarget.selectedVersionId(), target.selectedVersionId());
                // Detached historical cards stay readable and can be removed or retained while another shot is edited.
                if (!sameTarget) validateTarget(ownerId, projectId, target.canvasItemId(), target.selectedVersionId(), kind(target.role()));
                int reviewed = sameTarget ? oldTarget.reviewedRevision()
                        : approvals.storyboardResultRevision(ownerId, projectId, input.id(), target.canvasItemId(), target.selectedVersionId())
                                .map(generatedRevision -> Math.min(generatedRevision, revision)).orElse(revision);
                if (target.confirmReviewed()) reviewed = revision;
                productions.add(new Production(target.role(), target.canvasItemId(), target.selectedVersionId(),
                        reviewed, target.selectedVersionId() != null && reviewed < revision));
            }
            shots.add(new Shot(input.id(), input.code(), input.scene(), input.title(), input.description(),
                    input.dialogue(), input.durationSeconds(), input.sourceVersionId(), List.copyOf(input.referenceVersionIds()),
                    revision, List.copyOf(productions)));
            if (creativeChanged || old == null || !productionChoices(old.productions()).equals(productionChoices(productions))) compositionChanged = true;
        }
        Composition composition = null;
        if (request.composition() != null) {
            CompositionInput input = request.composition();
            if (input.canvasItemId() == null) throw invalid("composition");
            Composition old = prior.composition();
            boolean same = old != null && old.canvasItemId().equals(input.canvasItemId())
                    && Objects.equals(old.selectedVersionId(), input.selectedVersionId());
            if (!same) validateTarget(ownerId, projectId, input.canvasItemId(), input.selectedVersionId(), Artifact.Kind.VIDEO);
            boolean needsReview = same && input.selectedVersionId() != null && (old.needsReview() || compositionChanged);
            if (input.confirmReviewed()) needsReview = false;
            composition = new Composition(input.canvasItemId(), input.selectedVersionId(), needsReview);
        }
        return new Storyboard(projectId, Math.addExact(prior.version(), 1), List.copyOf(shots), composition);
    }

    private List<List<Object>> productionChoices(List<Production> productions) {
        // Slots have semantic roles, so UI serialization order is not a change to the film's inputs.
        return productions.stream().sorted(java.util.Comparator.comparing(Production::role))
                .map(p -> java.util.Arrays.<Object>asList(p.role(), p.canvasItemId(), p.selectedVersionId())).toList();
    }

    private boolean creativeChanged(Shot old, ShotInput input) {
        return !Objects.equals(old.description(), input.description()) || !Objects.equals(old.dialogue(), input.dialogue())
                || old.durationSeconds().compareTo(input.durationSeconds()) != 0
                || !Objects.equals(old.sourceVersionId(), input.sourceVersionId())
                || !old.referenceVersionIds().equals(input.referenceVersionIds());
    }

    private void validateInput(UUID ownerId, UUID projectId, ShotInput input) {
        text(input.code(), 80, "code"); text(input.scene(), 200, "scene"); text(input.title(), 200, "title");
        text(input.description(), 20_000, "description"); text(input.dialogue(), 20_000, "dialogue");
        if (input.durationSeconds() == null || input.durationSeconds().compareTo(new BigDecimal("0.1")) < 0
                || input.durationSeconds().stripTrailingZeros().scale() > 3
                || input.durationSeconds().compareTo(BigDecimal.valueOf(3600)) > 0
                || input.referenceVersionIds() == null || input.referenceVersionIds().size() > MAX_REFERENCES
                || input.productions() == null || input.productions().size() > Role.values().length) throw invalid("shot");
        if (input.sourceVersionId() != null && versionKind(ownerId, projectId, input.sourceVersionId()) != Artifact.Kind.TEXT) throw invalid("sourceVersionId");
        Set<UUID> references = new HashSet<>();
        for (UUID id : input.referenceVersionIds()) {
            if (id == null || !references.add(id) || versionKind(ownerId, projectId, id) == Artifact.Kind.TEXT) throw invalid("referenceVersionIds");
        }
    }

    private Artifact.Kind versionKind(UUID ownerId, UUID projectId, UUID id) {
        var version = artifacts.requireVersionById(ownerId, projectId, id);
        return artifacts.get(ownerId, projectId, version.artifactId()).artifact().kind();
    }

    private void validateTarget(UUID ownerId, UUID projectId, UUID itemId, UUID versionId, Artifact.Kind kind) {
        var item = items.requireArtifactItem(ownerId, projectId, itemId);
        if (artifacts.get(ownerId, projectId, item.subjectId()).artifact().kind() != kind) throw invalid("target.kind");
        if (versionId != null && canvas.listMediaVersions(ownerId, projectId, itemId).stream()
                .noneMatch(value -> value.id().equals(versionId))) throw invalid("selectedVersionId");
    }

    private Artifact.Kind kind(Role role) {
        return switch (role) { case KEYFRAME -> Artifact.Kind.IMAGE; case VIDEO -> Artifact.Kind.VIDEO; case AUDIO -> Artifact.Kind.AUDIO; };
    }
    private void text(String value, int max, String field) {
        if (value == null || value.length() > max) throw invalid(field);
    }
    private ApiProblemException invalid(String field) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "STORYBOARD_INVALID",
                ApiMessage.of("api.storyboard.invalid-title"), ApiMessage.of("api.storyboard.invalid-detail", field), false);
    }
    private ApiProblemException conflict() {
        return new ApiProblemException(HttpStatus.CONFLICT, "STORYBOARD_VERSION_CONFLICT",
                ApiMessage.of("api.storyboard.conflict-title"), ApiMessage.of("api.storyboard.conflict-detail"), false);
    }
    public record Save(long expectedVersion, List<ShotInput> shots, CompositionInput composition) {}
    public record ShotInput(UUID id, String code, String scene, String title, String description,
            String dialogue, BigDecimal durationSeconds, UUID sourceVersionId, List<UUID> referenceVersionIds,
            List<ProductionInput> productions) {}
    public record ProductionInput(Role role, UUID canvasItemId, UUID selectedVersionId, boolean confirmReviewed) {}
    public record CompositionInput(UUID canvasItemId, UUID selectedVersionId, boolean confirmReviewed) {}
}
