package dev.agenvas.plan.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.plan.application.ShotKeyframeSelection;
import dev.agenvas.plan.application.ShotKeyframeSelectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 阶段 B 关键帧选择的用户入口；只保存明确选择，不推断或自动切换图片。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/{runId}/shots/{shotId}/keyframe-selection")
public class ShotKeyframeSelectionController {

    /** 负责校验 Run、镜头和图片版本归属并保存显式选择。 */
    private final ShotKeyframeSelectionService selections;

    /** 注入会校验 Run、镜头和图片版本归属的应用服务。
     * @param selections 关键帧选择查询与更新服务
     */
    public ShotKeyframeSelectionController(ShotKeyframeSelectionService selections) {
        this.selections = selections;
    }

    /** 返回该 Run 下已持久化的精确选择；未选择时由服务返回未找到。
     * @param principal 当前认证用户
     * @param projectId 所属项目
     * @param runId 图片生成所在 Run
     * @param shotId 被选择关键帧的镜头
     * @return 镜头版本和图片版本的固定关联
     */
    @GetMapping
    public ShotKeyframeSelection get(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID runId,
            @PathVariable UUID shotId) {
        return selections.get(principal.userId(), projectId, runId, shotId);
    }

    /** 从本 Run 已完成的图片中选择一个关键帧，并以选择版本防止并发覆盖。
     * @param principal 作出选择的认证用户
     * @param projectId 所属项目
     * @param runId 图片生成所在 Run
     * @param shotId 目标镜头
     * @param request 镜头和图片精确版本及预期选择版本
     * @return 更新后的关键帧关联
     */
    @PutMapping
    public ShotKeyframeSelection select(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID runId,
            @PathVariable UUID shotId,
            @Valid @RequestBody SelectRequest request) {
        return selections.select(principal.userId(), projectId, runId, shotId,
                request.shotVersionId(), request.imageArtifactId(),
                request.imageVersionId(), request.expectedVersion());
    }

    /** 关键帧选择命令；首次选择可无版本，后续替换必须携带读取到的版本。
     * @param shotVersionId 图片所对应的镜头版本
     * @param imageArtifactId 被选图片产物
     * @param imageVersionId 固定的图片不可变版本
     * @param expectedVersion 已有选择版本；首次建立关系时为空
     */
    public record SelectRequest(@NotNull UUID shotVersionId,
            @NotNull UUID imageArtifactId, @NotNull UUID imageVersionId,
            Long expectedVersion) {}
}
