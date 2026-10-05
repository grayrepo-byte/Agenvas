package dev.agenvas.project.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 项目 REST 入口；所有读写都从认证主体取得所有者 ID，再由应用服务执行项目范围校验。 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    /** 执行项目授权、版本校验及生命周期变更。 */
    private final ProjectService projects;

    /** 注入项目用例服务，控制器本身不直接访问持久层。 */
    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    /** 创建归属当前管理员的项目。
     * @param principal 由 Spring Security 校验得到的管理员身份
     * @param request 已通过字段校验的项目名称和画幅
     * @return 新建项目及其初始版本信息
     */
    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @AuthenticationPrincipal AdminPrincipal principal,
            @Valid @RequestBody CreateProjectRequest request) {
        Project project = projects.create(principal.userId(), request.name(), request.aspectRatio());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(project));
    }

    /** 按当前管理员列出项目；游标分页避免偏移量分页在并发新增时跳项。
     * @param principal 当前管理员身份
     * @param includeArchived 是否同时包含已归档项目
     * @param cursor 上一页返回的不透明游标；为空时从第一页开始
     * @param limit 单页条数；未提供时由服务采用默认值
     * @return 本页项目和下一页游标（若还有后续项目）
     */
    @GetMapping
    public ProjectListResponse list(
            @AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        ProjectService.ProjectPage page =
                projects.list(principal.userId(), includeArchived, cursor, limit);
        return new ProjectListResponse(
                page.items().stream().map(ProjectResponse::from).toList(), page.nextCursor());
    }

    /** 读取当前管理员拥有的单个项目。
     * @param principal 当前管理员身份
     * @param projectId 路径中的项目 UUID
     * @return 项目当前状态与版本
     */
    @GetMapping("/{projectId}")
    public ProjectResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return ProjectResponse.from(projects.get(principal.userId(), projectId));
    }

    /** 更新项目设置；expectedVersion 不匹配时由服务拒绝覆盖并发修改。
     * @param principal 当前管理员身份
     * @param projectId 路径中的项目 UUID
     * @param request 可选的新名称、画幅及客户端读取时的版本
     * @return 更新后的项目状态与递增版本
     */
    @PatchMapping("/{projectId}")
    public ProjectResponse update(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request) {
        return ProjectResponse.from(projects.update(
                principal.userId(),
                projectId,
                request.expectedVersion(),
                request.name(),
                request.aspectRatio()));
    }

    /** 归档项目；服务按请求版本执行条件更新，归档后活动项目用例会拒绝该项目。
     * @param principal 当前管理员身份
     * @param projectId 路径中的项目 UUID
     * @param request 客户端读取到的项目版本
     * @return 已归档项目及其新版本
     */
    @PostMapping("/{projectId}/archive")
    public ProjectResponse archive(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody ArchiveProjectRequest request) {
        return ProjectResponse.from(
                projects.archive(principal.userId(), projectId, request.expectedVersion()));
    }

    /** 新建项目命令。
     * @param name 用户可识别的项目名称
     * @param aspectRatio 后续镜头规划与媒体生成采用的画幅
     */
    public record CreateProjectRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull Project.AspectRatio aspectRatio) {}

    /** 项目设置更新命令；字段为空表示不修改，版本用于避免丢失并发更新。
     * @param expectedVersion 客户端读取项目时的版本
     * @param name 新名称；为空时保留原名称
     * @param aspectRatio 新画幅；为空时保留原画幅
     */
    public record UpdateProjectRequest(
            @PositiveOrZero long expectedVersion,
            @Size(min = 1, max = 120) String name,
            Project.AspectRatio aspectRatio) {}

    /** 项目归档命令。
     * @param expectedVersion 客户端读取项目时的版本
     */
    public record ArchiveProjectRequest(@PositiveOrZero long expectedVersion) {}

    /** 对外返回的项目快照。
     * @param id 项目 UUID 的字符串形式
     * @param name 项目名称
     * @param aspectRatio 项目画幅
     * @param status 当前生命周期状态
     * @param version 并发控制版本
     * @param createdAt 创建时间
     * @param updatedAt 最近一次内容设置更新时间
     * @param archivedAt 归档时间；未归档时为空
     */
    public record ProjectResponse(
            String id,
            String name,
            Project.AspectRatio aspectRatio,
            Project.Status status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt) {

        /** 将领域项目映射为不暴露内部对象的 API 快照。
         * @param project 已完成所有者范围校验的领域项目
         * @return 字段与领域快照一致的响应 DTO
         */
        public static ProjectResponse from(Project project) {
            return new ProjectResponse(
                    project.id().toString(),
                    project.name(),
                    project.aspectRatio(),
                    project.status(),
                    project.version(),
                    project.createdAt(),
                    project.updatedAt(),
                    project.archivedAt());
        }
    }

    /** 项目列表分页结果。
     * @param items 当前页项目
     * @param nextCursor 下一页起点；没有后续数据时为空
     */
    public record ProjectListResponse(List<ProjectResponse> items, String nextCursor) {}
}
