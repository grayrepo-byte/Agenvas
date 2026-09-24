package dev.agenvas.export.api;

import dev.agenvas.export.application.ProjectExportManifestService;
import dev.agenvas.identity.application.AdminPrincipal;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 项目 JSON 导出清单下载入口；媒体字节仍须通过各自的鉴权接口读取。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/export-manifest")
public class ProjectExportManifestController {

    /** 在一致性快照内构造脱敏的项目清单。 */
    private final ProjectExportManifestService manifests;

    /** 注入清单构建服务。
     * @param manifests 执行项目授权并构造导出快照的服务
     */
    public ProjectExportManifestController(ProjectExportManifestService manifests) {
        this.manifests = manifests;
    }

    /** 下载脱敏的一致性项目清单，不包含签名媒体链接或原始配置。
     * @param principal 当前认证用户
     * @param projectId 要导出的项目
     * @return JSON 附件响应
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProjectExportManifestService.Manifest> get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"agenvas-project-" + projectId + ".json\"")
                .body(manifests.build(principal.userId(), projectId));
    }
}
