package dev.agenvas.canvas.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.llm.application.AgentMediaApprovalOwnershipReadService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.storyboard.application.StoryboardSelectedMediaReadAccessService;
import dev.agenvas.task.application.TaskCanvasOwnershipReadService;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixed, private copy snapshots and content-only undo guards.
 * Every write runs inside the caller's project event transaction and emits no nested event.
 * These snapshots contain ordinary saved creative content, never provider configuration or task bodies.
 */
@Service
public class CanvasCopySupport {
    private static final int SCHEMA_VERSION = 1;
    private final ProjectService projects;
    private final ArtifactService artifacts;
    private final MediaDraftService drafts;
    private final CanvasItemQueryService items;
    private final CanvasConnectionService connections;
    private final CharacterService characters;
    private final TaskCanvasOwnershipReadService tasks;
    private final AgentMediaApprovalOwnershipReadService approvals;
    private final StoryboardSelectedMediaReadAccessService storyboards;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CanvasCopySupport(ProjectService projects, ArtifactService artifacts, MediaDraftService drafts,
            CanvasItemQueryService items, CanvasConnectionService connections, CharacterService characters,
            TaskCanvasOwnershipReadService tasks, AgentMediaApprovalOwnershipReadService approvals,
            StoryboardSelectedMediaReadAccessService storyboards, ObjectMapper mapper, Clock clock) {
        this.projects = projects; this.artifacts = artifacts; this.drafts = drafts; this.items = items;
        this.connections = connections; this.characters = characters; this.tasks = tasks;
        this.approvals = approvals; this.storyboards = storyboards; this.mapper = mapper; this.clock = clock;
    }

    /** Layout/title/lock changes are excluded so they can be undone before undoing the copy.
     * Draft, choice epoch, history, bindings and every durable downstream use protect later work.
     */
    @Transactional(readOnly = true)
    public JsonNode capture(UUID owner, UUID project, CanvasItem requested) {
        CanvasItem item = items.requireArtifactItem(owner, project, requested.id());
        var artifact = artifacts.get(owner, project, item.subjectId());
        Artifact.Kind kind = artifact.artifact().kind();
        ObjectNode guard = document().put("subjectId", item.subjectId().toString()).put("kind", kind.name());
        if (item.selectedVersionId() == null) guard.putNull("selectedVersionId");
        else guard.put("selectedVersionId", item.selectedVersionId().toString());
        if (kind == Artifact.Kind.TEXT) guard.set("text", textGuard(owner, project, item.subjectId()));
        else {
            guard.put("artifactArchived", artifact.artifact().archivedAt() != null);
            var media = items.mediaCopyGuard(owner, project, item.id());
            guard.put("mediaSelectionEpoch", media.mediaSelectionEpoch());
            guard.set("mediaVersionIds", mapper.valueToTree(media.mediaVersionIds()));
            guard.set("mediaDraft", mapper.valueToTree(drafts.get(owner, project, item.id())));
            guard.set("characters", characters.canvasCopyGuard(owner, project, item.id()));
        }
        var topology = connections.copyGuard(owner, project, item.id());
        guard.put("topologyEpoch", topology.epoch());
        guard.set("connections", mapper.valueToTree(topology.connections()));
        guard.put("hasTaskOwnership", tasks.hasOwnership(owner, project, item.id(), kind == Artifact.Kind.TEXT ? item.subjectId() : null));
        guard.put("hasApprovalOwnership", approvals.hasOwnership(owner, project, item.id()));
        guard.put("hasStoryboardReference", storyboards.hasCanvasReference(owner, project, item.id()));
        return guard;
    }

    /** Allocates independent text content, or a manual-only fixed media draft and character state. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedCopy prepareCloneWithinChange(UUID owner, UUID project, CanvasItem requested, UUID targetId) {
        projects.requireActiveProject(owner, project);
        CanvasItem source = items.requireArtifactItem(owner, project, requested.id());
        var artifact = artifacts.get(owner, project, source.subjectId());
        if (artifact.artifact().archivedAt() != null) throw conflict();
        ObjectNode frozen = document().put("kind", artifact.artifact().kind().name()).put("targetId", targetId.toString());
        if (artifact.artifact().kind() == Artifact.Kind.TEXT) {
            var copy = artifacts.createTextCopyWithinChange(owner, project, source.title(), artifact.resourceDefaultVersion().content().deepCopy());
            frozen.put("subjectId", copy.artifact().id().toString()).putNull("selectedVersionId");
            frozen.set("text", textGuard(owner, project, copy.artifact().id()));
            return new PreparedCopy(copy.artifact().id(), null, frozen);
        }
        if (source.selectedVersionId() != null) artifacts.requireVersion(owner, project, source.subjectId(), source.selectedVersionId());
        MediaDraft sourceDraft = drafts.get(owner, project, source.id());
        var now = clock.instant();
        List<MediaDraft.MediaInput> inputs = sourceDraft.mediaInputs().stream().map(input ->
                new MediaDraft.MediaInput(input.versionId(), input.artifactId(), input.role(), input.order(), input.color(),
                        List.of(new MediaDraft.InputSource(UUID.randomUUID(), MediaDraft.SourceType.MANUAL, null)))).toList();
        MediaDraft draft = new MediaDraft(project, targetId, sourceDraft.prompt(), sourceDraft.parameters().deepCopy(),
                sourceDraft.durationSeconds(), sourceDraft.capabilityId(), sourceDraft.styleId(), sourceDraft.videoInputMode(),
                inputs, List.copyOf(sourceDraft.mentions()), sourceDraft.displayMode(), 1, now, now);
        frozen.put("subjectId", source.subjectId().toString());
        if (source.selectedVersionId() == null) frozen.putNull("selectedVersionId");
        else frozen.put("selectedVersionId", source.selectedVersionId().toString());
        frozen.set("mediaDraft", mapper.valueToTree(draft));
        frozen.set("characters", characters.prepareCanvasCopy(owner, project, source.id(), draft));
        return new PreparedCopy(source.subjectId(), source.selectedVersionId(), frozen);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void installWithinChange(UUID owner, UUID project, CanvasItem target, JsonNode frozen) {
        restoreWithinChange(owner, project, target, frozen, null);
    }

    /** Redo uses the original copy snapshot, while advancing CAS beyond the deleted incarnation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreWithinChange(UUID owner, UUID project, CanvasItem target, JsonNode frozen, JsonNode previousGuard) {
        projects.requireActiveProject(owner, project);
        validateSnapshot(target, frozen);
        var artifact = artifacts.get(owner, project, target.subjectId());
        if (!artifact.artifact().kind().name().equals(frozen.path("kind").asText()) || artifact.artifact().archivedAt() != null) throw conflict();
        if (artifact.artifact().kind() == Artifact.Kind.TEXT) {
            if (!textSemantics(textGuard(owner, project, target.subjectId())).equals(textSemantics(frozen.path("text")))) throw conflict();
            if (previousGuard != null && (tasks.hasOwnership(owner, project, target.id(), target.subjectId())
                    || approvals.hasOwnership(owner, project, target.id()) || storyboards.hasCanvasReference(owner, project, target.id()))) throw conflict();
            if (previousGuard != null) {
                long expectedVersion = previousGuard.at("/text/artifact/version").asLong(-1);
                UUID expectedContent = UUID.fromString(frozen.at("/text/currentVersionId").asText());
                artifacts.fenceTextCopyWithinChange(owner, project, target.subjectId(), expectedVersion, expectedContent);
            }
            return;
        }
        if (target.selectedVersionId() != null) artifacts.requireVersion(owner, project, target.subjectId(), target.selectedVersionId());
        long minimumDraftVersion = previousGuard == null ? 1 : previousGuard.path("mediaDraft").path("version").asLong() + 1;
        long minimumBindingVersion = previousGuard == null ? 1
                : previousGuard.path("characters").path("binding").path("version").asLong() + 1;
        drafts.installCopyWithinChange(owner, project, target.id(), mapper.treeToValue(frozen.path("mediaDraft"), MediaDraft.class), minimumDraftVersion);
        characters.installCanvasCopyWithinChange(owner, project, target.id(), frozen.path("characters"), minimumBindingVersion);
    }

    private ObjectNode textGuard(UUID owner, UUID project, UUID artifactId) {
        var view = artifacts.get(owner, project, artifactId);
        ObjectNode value = mapper.createObjectNode();
        value.set("artifact", mapper.valueToTree(view.artifact()));
        value.put("currentVersionId", view.resourceDefaultVersion().id().toString());
        value.set("versionIds", mapper.valueToTree(artifacts.listVersions(owner, project, artifactId).stream()
                .map(version -> version.id().toString()).sorted().toList()));
        return value;
    }

    private void validateSnapshot(CanvasItem target, JsonNode frozen) {
        UUID selected = frozen.hasNonNull("selectedVersionId") ? UUID.fromString(frozen.path("selectedVersionId").asText()) : null;
        if (frozen.path("schemaVersion").asInt() != SCHEMA_VERSION
                || !target.id().toString().equals(frozen.path("targetId").asText())
                || !target.subjectId().toString().equals(frozen.path("subjectId").asText())
                || !Objects.equals(selected, target.selectedVersionId())) throw conflict();
    }

    /** Redo changes only the Artifact CAS fence, so fixed content comparison excludes that
     * metadata while retaining title, archive state, exact selection and the full history IDs.
     */
    private JsonNode textSemantics(JsonNode value) {
        ObjectNode copy = (ObjectNode) value.deepCopy();
        if (copy.path("artifact") instanceof ObjectNode artifact) {
            artifact.remove("version");
            artifact.remove("updatedAt");
        }
        return normalized(copy);
    }

    private JsonNode normalized(JsonNode value) { return mapper.readTree(mapper.writeValueAsString(value)); }
    private ObjectNode document() { return mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION); }
    private ApiProblemException conflict() {
        return new ApiProblemException(HttpStatus.CONFLICT, "CANVAS_VERSION_CONFLICT",
                ApiMessage.of("api.canvas-service.canvas-card-updated"),
                ApiMessage.of("api.canvas-service.the-canvas-card-has-been-modified-by-other-requests-please"), false);
    }

    public record PreparedCopy(UUID subjectId, UUID selectedVersionId, JsonNode frozen) {}
}
