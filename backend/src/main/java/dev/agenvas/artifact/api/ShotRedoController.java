package dev.agenvas.artifact.api;

import dev.agenvas.artifact.application.ShotRedoService;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 镜头局部重做的认证 REST 入口；把用户编辑保存为新版本，不直接触发媒体生成。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/shots")
public class ShotRedoController {

    /** 执行镜头和场景版本创建的应用服务。 */
    private final ShotRedoService redo;

    /** 注入局部重做用例服务。
     * @param redo 负责校验目标版本并创建修订的服务
     */
    public ShotRedoController(ShotRedoService redo) {
        this.redo = redo;
    }

    /** 保存镜头的新内容版本，并可选地为共享场景创建新版本。
     * @param principal 当前认证用户
     * @param projectId 路径项目 UUID
     * @param shotId 要修订的镜头 UUID
     * @param request 编辑字段和客户端读取到的目标版本
     * @return 新建的镜头版本及可能产生的场景版本
     */
    @PostMapping("/{shotId}/revisions")
    public ResponseEntity<Response> revise(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID shotId,
            @Valid @RequestBody Request request) {
        ShotRedoService.Result result = redo.revise(principal.userId(), projectId, shotId,
                new ShotRedoService.Request(request.expectedShotVersionId(),
                        request.expectedShotArtifactVersion(), request.description(),
                        request.camera(), request.action(), request.durationMs(),
                        request.scene() == null ? null : new ShotRedoService.SceneEdit(
                                request.scene().name(), request.scene().location(),
                                request.scene().timeOfDay(), request.scene().lighting(),
                                request.scene().style())));
        return ResponseEntity.status(HttpStatus.CREATED).body(new Response(
                ArtifactController.ArtifactResponse.from(result.shot()),
                result.scene() == null ? null
                        : ArtifactController.ArtifactResponse.from(result.scene())));
    }

    /** 镜头修订命令；服务以镜头版本和产物版本双重校验避免覆盖并发修改。
     * @param expectedShotVersionId 客户端选中的镜头内容版本
     * @param expectedShotArtifactVersion 客户端读取时镜头产物的版本号
     * @param description 新镜头描述
     * @param camera 新镜头机位描述
     * @param action 新镜头动作描述
     * @param durationMs 镜头时长；为空时保留领域默认行为
     * @param scene 可选场景编辑；为空时继续引用现有场景
     */
    public record Request(@NotNull UUID expectedShotVersionId,
            @PositiveOrZero long expectedShotArtifactVersion,
            @NotBlank @Size(max = 4000) String description,
            @NotBlank @Size(max = 1000) String camera,
            @NotBlank @Size(max = 2000) String action,
            Integer durationMs,
            @Valid SceneEdit scene) {}

    /** 场景修订字段；各字段为空时沿用当前选中场景版本中的值。
     * @param name 场景名称
     * @param location 场景地点
     * @param timeOfDay 场景时间段
     * @param lighting 场景光线描述
     * @param style 场景视觉风格
     */
    public record SceneEdit(@Size(min = 1, max = 120) String name,
            @Size(min = 1, max = 500) String location,
            @Size(min = 1, max = 80) String timeOfDay,
            @Size(min = 1, max = 1000) String lighting,
            @Size(min = 1, max = 1000) String style) {}

    /** 修订结果，不包含项目中未变更的其他产物。
     * @param shot 新镜头版本
     * @param scene 新场景版本；未编辑场景时为空
     */
    public record Response(ArtifactController.ArtifactResponse shot,
            ArtifactController.ArtifactResponse scene) {}
}
