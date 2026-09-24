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

/** 创建目标镜头的新内容版本；必要时为共享场景另建版本，不修改其他镜头的旧引用。 */
@Service
public class ShotRedoService {

    /** 验证归属并执行不可变产物版本修订。 */
    private final ArtifactService artifacts;
    /** 查找镜头当前场景版本对应的产物身份。 */
    private final ArtifactRepository repository;

    /** 注入产物校验与场景版本定位能力。 */
    public ShotRedoService(ArtifactService artifacts, ArtifactRepository repository) {
        this.artifacts = artifacts;
        this.repository = repository;
    }

    /** 在一个事务内修订镜头和可选场景；任一步失败都会回滚，旧媒体版本保留为历史。 */
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
        // 镜头正文变化后，清除基于旧正文生成的媒体选择，避免旧图视频继续冒充当前结果。
        newShot.remove("selectedImageVersionId");
        newShot.remove("selectedVideoVersionId");
        ArtifactService.ArtifactView revisedShot = artifacts.revise(ownerId, projectId,
                shotId, request.expectedShotArtifactVersion(), null, newShot);
        return new Result(revisedShot, revisedScene);
    }

    /** 仅更新请求明确提供的场景字段，并拒绝空补丁。 */
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

    /** 从镜头正文读取场景版本 ID，并拒绝无法解析的历史引用。 */
    private UUID requiredUuid(JsonNode node) {
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException failure) {
            throw invalid("镜头场景版本 ID 无效。");
        }
    }

    /** 构造请求字段或场景引用无效时的 400 问题响应。 */
    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "SHOT_REDO_INVALID",
                "镜头修改无效", detail, false);
    }

    /** 构造镜头或共享场景当前版本已变化时的 409 问题响应。 */
    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "SHOT_REDO_CONFLICT",
                "镜头版本已变化", detail, false);
    }

    /** 仅接收可编辑正文；顺序及语义引用仍由服务端保留。
     * @param expectedShotVersionId 用户读取的镜头内容版本 ID
     * @param expectedShotArtifactVersion 镜头产物的并发控制版本
     * @param description 新镜头描述
     * @param camera 新机位说明
     * @param action 新动作说明
     * @param durationMs 可选新时长，单位毫秒
     * @param scene 可选共享场景字段补丁
     */
    public record Request(UUID expectedShotVersionId, long expectedShotArtifactVersion,
            String description, String camera, String action, Integer durationMs,
            SceneEdit scene) {}

    /** 共享场景的可选局部修订，应用后生成独立新版本。
     * @param name 新名称；为空时沿用当前值
     * @param location 新地点；为空时沿用当前值
     * @param timeOfDay 新时段；为空时沿用当前值
     * @param lighting 新光线说明；为空时沿用当前值
     * @param style 新风格说明；为空时沿用当前值
     */
    public record SceneEdit(String name, String location, String timeOfDay,
            String lighting, String style) {}

    /** 本次修订产生的新镜头和场景选择，供后续绑定与计划使用。
     * @param shot 新版本镜头
     * @param scene 新版本共享场景；未修改场景时为空
     */
    public record Result(ArtifactService.ArtifactView shot,
            ArtifactService.ArtifactView scene) {}
}
