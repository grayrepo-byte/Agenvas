package dev.agenvas.export.api;

import dev.agenvas.export.application.MediaExportService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.api.TaskController.TaskResponse;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 项目级顺序视频导出的创建、查询和取消入口；导出输入在后台执行前已固定。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/exports")
public class MediaExportController {

    /** 创建导出任务并读取导出历史。 */
    private final MediaExportService exports;
    /** 按所有者和项目范围读取持久化任务。 */
    private final TaskService tasks;

    /** 注入导出用例及任务读取服务。
     * @param exports 导出输入校验与任务创建服务
     * @param tasks 项目范围内的任务查询服务
     */
    public MediaExportController(MediaExportService exports, TaskService tasks) {
        this.exports = exports;
        this.tasks = tasks;
    }

    /** 固定输入版本和片段顺序后受理导出任务；HTTP 请求期间不会启动 FFmpeg。
     * @param principal 当前认证用户
     * @param projectId 导出所属项目
     * @param idempotencyKey 客户端导出命令键，重复提交返回同一任务
     * @param request 有序视频版本及剪辑区间
     * @return 已持久化任务及其状态查询地址信息
     */
    @PostMapping
    public ResponseEntity<TaskResponse> create(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateExportRequest request) {
        Task task = exports.create(principal.userId(), projectId, idempotencyKey,
                request.segments().stream().map(item -> new MediaExportService.SegmentRequest(
                        item.videoArtifactId(), item.videoVersionId(),
                        item.startSeconds(), item.endSeconds())).toList());
        return ResponseEntity.accepted().body(TaskResponse.from(task));
    }

    /** 查询项目最近的持久化导出任务。
     * @param principal 当前认证用户
     * @param projectId 导出所属项目
     * @return 按创建时间排序的导出历史
     */
    @GetMapping
    public List<TaskResponse> list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return exports.list(principal.userId(), projectId).stream()
                .map(TaskResponse::from).toList();
    }

    /** 读取指定导出；历史导出不依赖项目当前是否占用 Agent Run 槽位。
     * @param principal 当前认证用户
     * @param projectId 任务所属项目
     * @param taskId 导出任务 UUID
     * @return 导出任务状态
     */
    @GetMapping("/{taskId}")
    public TaskResponse get(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID taskId) {
        Task task = tasks.get(principal.userId(), projectId, taskId);
        if (task.kind() != Task.Kind.MEDIA_EXPORT) {
            throw new ApiProblemException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                    "导出不存在", "该项目没有此导出任务。", false);
        }
        return TaskResponse.from(task);
    }

    /** 请求停止本地导出编排，不表示外部媒体生成已停止或费用会退回。
     * @param principal 当前认证用户
     * @param projectId 任务所属项目
     * @param taskId 要取消的导出任务
     * @return 持久化取消请求后的任务状态
     */
    @PostMapping("/{taskId}/cancel")
    public TaskResponse cancel(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID taskId) {
        return TaskResponse.from(exports.cancel(principal.userId(), projectId, taskId));
    }

    /** 导出请求；段落顺序即最终播放顺序。
     * @param segments 一到六个固定视频版本及其时间区间
     */
    public record CreateExportRequest(
            @NotEmpty @Size(max = 6) List<@Valid Segment> segments) {}

    /** 视频区间采用整数秒闭开范围，Worker 会依据归档媒体时长再次校验。
     * @param videoArtifactId 视频产物
     * @param videoVersionId 固定的不可变视频版本
     * @param startSeconds 包含的起始秒数
     * @param endSeconds 不包含的结束秒数
     */
    public record Segment(@NotNull UUID videoArtifactId, @NotNull UUID videoVersionId,
            @NotNull @Min(0) @Max(59) Integer startSeconds,
            @NotNull @Min(1) @Max(60) Integer endSeconds) {}
}
