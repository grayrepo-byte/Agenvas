package dev.agenvas.project.api;

import dev.agenvas.agent.api.AgentInstanceController.AgentResponse;
import dev.agenvas.canvas.api.CanvasController.CanvasResponse;
import dev.agenvas.canvas.api.CanvasConnectionController.ConnectionView;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.project.application.ProjectSnapshotService;
import dev.agenvas.project.api.ProjectController.ProjectResponse;
import dev.agenvas.run.api.AgentRunController.RunResponse;
import dev.agenvas.task.api.TaskController.TaskResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供页面刷新恢复快照及后续事件补发水位的 REST 边界。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/snapshot")
public class ProjectSnapshotController {

    /** 在一致性事务中读取项目恢复数据。 */
    private final ProjectSnapshotService snapshots;

    /** 注入组装一致性项目快照的服务。
     * @param snapshots 执行授权和一致性读取
     */
    public ProjectSnapshotController(ProjectSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    /** 将同一一致性快照映射为 API DTO，供客户端从 snapshotSeq 开始订阅事件。 */
    @GetMapping
    public ProjectSnapshotResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        ProjectSnapshotService.ProjectSnapshot snapshot =
                snapshots.snapshot(principal.userId(), projectId);
        return new ProjectSnapshotResponse(
                ProjectResponse.from(snapshot.project()),
                CanvasResponse.from(snapshot.canvas()),
                snapshot.connections().stream().map(ConnectionView::from).toList(),
                snapshot.agents().stream().map(AgentResponse::from).toList(),
                snapshot.activeRun() == null ? null : RunResponse.from(snapshot.activeRun()),
                snapshot.activeTasks().stream().map(TaskResponse::from).toList(),
                snapshot.unknownTasks().stream().map(TaskResponse::from).toList(),
                snapshot.snapshotSeq());
    }

    /**
     * 页面恢复快照；activeRun 为空代表项目没有活动槽位，snapshotSeq 是后续事件补发起点。
     *
     * @param project 项目设置与版本
     * @param canvas 画布内容投影
     * @param connections 固定精确图片版本的持久化画布连线
     * @param agents Agent 配置投影
     * @param activeRun 当前活动 Run；空值表示空闲
     * @param activeTasks 活动 Run 的任务状态
     * @param unknownTasks 未决外部提交任务
     * @param snapshotSeq 数据库快照对应的项目事件水位
     */
    public record ProjectSnapshotResponse(
            ProjectResponse project,
            CanvasResponse canvas,
            List<ConnectionView> connections,
            List<AgentResponse> agents,
            RunResponse activeRun,
            List<TaskResponse> activeTasks,
            List<TaskResponse> unknownTasks,
            long snapshotSeq) {}
}
