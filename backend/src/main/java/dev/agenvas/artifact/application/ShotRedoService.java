package dev.agenvas.artifact.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Revisions a single shot and optionally its pinned shared scene without touching sibling shots. */
@Service
public class ShotRedoService {

    private final ArtifactService artifacts;
    private final ArtifactRepository repository;

    public ShotRedoService(ArtifactService artifacts, ArtifactRepository repository) {
        this.artifacts = artifacts;
        this.repository = repository;
    }

    /** One transaction creates both immutable revisions or neither; old media stays historical. */
    @Transactional
    public Result revise(UUID ownerId, UUID projectId, UUID shotId, Request request) {
        if (request == null || request.expectedShotVersionId() == null
                || request.description() == null || request.camera() == null
                || request.action() == null) {
            throw invalid("镜头版本与完整修改内容必填。");
        }
        ArtifactService.ArtifactView shot = artifacts.get(ownerId, projectId, shotId);
        if (shot.artifact().kind() != Artifact.Kind.SHOT
                || shot.artifact().version() != request.expectedShotArtifactVersion()
                || !shot.currentVersion().id().equals(request.expectedShotVersionId())) {
            throw conflict("目标镜头已变化，请重新读取后再修改。");
        }
        if (!(shot.currentVersion().content() instanceof ObjectNode currentShot)) {
            throw invalid("目标镜头内容无效。");
        }
        ObjectNode newShot = currentShot.deepCopy();
        newShot.put("description", request.description());
        newShot.put("camera", request.camera());
        newShot.put("action", request.action());
        if (request.durationMs() != null) newShot.put("durationMs", request.durationMs());

        ArtifactService.ArtifactView revisedScene = null;
        if (request.scene() != null) {
            UUID sceneVersionId = requiredUuid(currentShot.path("sceneVersionId"));
            ArtifactRepository.VersionTarget target = repository
                    .findVersionTarget(projectId, sceneVersionId)
                    .orElseThrow(() -> invalid("镜头没有有效的场景版本。"));
            if (target.kind() != Artifact.Kind.SCENE) {
                throw invalid("镜头引用不是场景版本。");
            }
            ArtifactService.ArtifactView scene = artifacts.get(ownerId, projectId,
                    target.artifactId());
            if (!scene.currentVersion().id().equals(sceneVersionId)) {
                throw conflict("共享场景已被修改，请先重新检查场景版本。");
            }
            if (!(scene.currentVersion().content() instanceof ObjectNode currentScene)) {
                throw invalid("共享场景内容无效。");
            }
            ObjectNode newScene = currentScene.deepCopy();
            applyScenePatch(newScene, request.scene());
            revisedScene = artifacts.revise(ownerId, projectId, target.artifactId(),
                    scene.artifact().version(), null, newScene);
            newShot.put("sceneVersionId", revisedScene.currentVersion().id().toString());
        }
        // A changed shot cannot silently keep a media choice generated from its old content.
        newShot.remove("selectedImageVersionId");
        newShot.remove("selectedVideoVersionId");
        ArtifactService.ArtifactView revisedShot = artifacts.revise(ownerId, projectId,
                shotId, request.expectedShotArtifactVersion(), null, newShot);
        return new Result(revisedShot, revisedScene);
    }

    private void applyScenePatch(ObjectNode scene, SceneEdit patch) {
        if (patch.name() == null && patch.location() == null && patch.timeOfDay() == null
                && patch.lighting() == null && patch.style() == null) {
            throw invalid("场景修改至少需要一个字段。");
        }
        if (patch.name() != null) scene.put("name", patch.name());
        if (patch.location() != null) scene.put("location", patch.location());
        if (patch.timeOfDay() != null) scene.put("timeOfDay", patch.timeOfDay());
        if (patch.lighting() != null) scene.put("lighting", patch.lighting());
        if (patch.style() != null) scene.put("style", patch.style());
    }

    private UUID requiredUuid(JsonNode node) {
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException failure) {
            throw invalid("镜头场景版本 ID 无效。");
        }
    }

    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "SHOT_REDO_INVALID",
                "镜头修改无效", detail, false);
    }

    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "SHOT_REDO_CONFLICT",
                "镜头版本已变化", detail, false);
    }

    /** Only mutable fields are accepted; order and semantic references remain server-owned. */
    public record Request(UUID expectedShotVersionId, long expectedShotArtifactVersion,
            String description, String camera, String action, Integer durationMs,
            SceneEdit scene) {}

    /** Optional partial replacement of a shared scene into a fresh version. */
    public record SceneEdit(String name, String location, String timeOfDay,
            String lighting, String style) {}

    /** Exact new selections returned for subsequent binding and planning. */
    public record Result(ArtifactService.ArtifactView shot,
            ArtifactService.ArtifactView scene) {}
}
