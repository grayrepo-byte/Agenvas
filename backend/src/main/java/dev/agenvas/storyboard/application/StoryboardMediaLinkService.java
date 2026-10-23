package dev.agenvas.storyboard.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.storyboard.domain.Storyboard;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Narrow shot provenance boundary, independent of media approval orchestration. */
@Service
public class StoryboardMediaLinkService {
    private final StoryboardRepository boards;
    private final ProjectService projects;

    public StoryboardMediaLinkService(StoryboardRepository boards, ProjectService projects) {
        this.boards = boards;
        this.projects = projects;
    }

    /** Called under the project event lock so creative edits cannot race the frozen revision. */
    @Transactional(readOnly = true)
    public Link freeze(UUID ownerId, UUID projectId, UUID shotId, int expectedRevision, Artifact.Kind kind) {
        return freezeWithFingerprint(ownerId, projectId, shotId, expectedRevision, kind).link();
    }

    @Transactional(readOnly = true)
    public Frozen freezeWithFingerprint(UUID ownerId, UUID projectId, UUID shotId, int expectedRevision, Artifact.Kind kind) {
        projects.get(ownerId, projectId);
        Storyboard.Shot shot = shot(projectId, shotId);
        if (shot == null || shot.revision() != expectedRevision) throw problem(false);
        Storyboard.Role role = switch (kind) {
            case IMAGE -> Storyboard.Role.KEYFRAME;
            case VIDEO -> Storyboard.Role.VIDEO;
            case AUDIO -> Storyboard.Role.AUDIO;
            default -> throw problem(false);
        };
        return new Frozen(new Link(shot.id(), shot.code(), shot.revision(), role), fingerprint(shot));
    }

    /** A different shot, ordering or label change does not invalidate the approved media input. */
    @Transactional(readOnly = true)
    public void requireCurrent(UUID ownerId, UUID projectId, Link link) {
        requireCurrent(ownerId, projectId, link, null);
    }

    @Transactional(readOnly = true)
    public void requireCurrent(UUID ownerId, UUID projectId, Link link, String expectedCreativeHash) {
        projects.get(ownerId, projectId);
        Storyboard.Shot shot = shot(projectId, link.shotId());
        if (shot == null || shot.revision() != link.shotRevision()
                || expectedCreativeHash != null && !expectedCreativeHash.equals(fingerprint(shot))) throw problem(true);
    }

    private String fingerprint(Storyboard.Shot shot) {
        // The private creative fingerprint also fences delete/recreate reuse of a shot UUID.
        var fields = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        fields.add(shot.description()).add(shot.dialogue()).add(shot.durationSeconds().stripTrailingZeros().toPlainString());
        fields.add(shot.sourceVersionId() == null ? null : shot.sourceVersionId().toString());
        shot.referenceVersionIds().forEach(id -> fields.add(id.toString()));
        return dev.agenvas.shared.crypto.Sha256.hex(fields.toString());
    }

    private Storyboard.Shot shot(UUID projectId, UUID shotId) {
        return boards.current(projectId).stream().flatMap(board -> board.shots().stream())
                .filter(shot -> shot.id().equals(shotId)).findFirst().orElse(null);
    }

    private ApiProblemException problem(boolean stale) {
        return new ApiProblemException(stale ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST,
                stale ? "AGENT_MEDIA_APPROVAL_STALE" : "TOOL_ARGUMENT_INVALID",
                ApiMessage.of(stale ? "api.agent-media-approval.conflict-title" : "api.agent-media-approval.invalid-title"),
                ApiMessage.of(stale ? "api.agent-media-approval.conflict-detail" : "api.agent-media-approval.invalid-detail", "storyboard-shot-revision"), false);
    }

    public record Link(UUID shotId, String shotCode, int shotRevision, Storyboard.Role role) {}
    public record Frozen(Link link, String creativeHash) {}
}
