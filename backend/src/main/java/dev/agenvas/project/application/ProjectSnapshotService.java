package dev.agenvas.project.application;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 在一个 MVCC 一致性快照中组装项目恢复状态及 SSE 事件水位。 */
@Service
public class ProjectSnapshotService {

    /** 核验调用者对项目的读取权限。 */
    private final ProjectService projects;
    /** 读取与其他快照数据同一事务视图中的事件水位和活动槽位。 */
    private final ProjectRepository projectRepository;
    /** 读取画布投影。 */
    private final CanvasService canvas;
    /** 读取项目 Agent 配置和固定输入。 */
    private final AgentInstanceService agents;
    /** 按项目锚点读取活动 Run。 */
    private final AgentRunService runs;
    /** 读取活动 Run 任务和待核对 UNKNOWN 任务。 */
    private final TaskService tasks;

    /** 注入快照所需的项目、画布、Agent、Run 和任务读取边界。
     * @param projects 检查项目授权并读取一致性锚点
     * @param projectRepository 获取数据库快照水位
     * @param canvas 读取画布空间项
     * @param agents 读取卡片与固定绑定
     * @param runs 读取活动及历史 Run 状态
     * @param tasks 读取项目任务状态
     */
    public ProjectSnapshotService(
            ProjectService projects,
            ProjectRepository projectRepository,
            CanvasService canvas,
            AgentInstanceService agents,
            AgentRunService runs,
            TaskService tasks) {
        this.projects = projects;
        this.projectRepository = projectRepository;
        this.canvas = canvas;
        this.agents = agents;
        this.runs = runs;
        this.tasks = tasks;
    }

    /**
     * 在 PostgreSQL REPEATABLE READ 事务内读取项目、事件水位、画布、Agent、活动 Run 和任务。
     * 返回的 snapshotSeq 可直接作为后续 SSE 补发的独占游标，不会遗漏快照并发期间已提交的事件。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId 要恢复的项目
     * @return 与单一数据库快照一致的项目恢复数据
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectSnapshot snapshot(UUID ownerId, UUID projectId) {
        Project project = projects.get(ownerId, projectId);
        ProjectRepository.SnapshotAnchor anchor = projectRepository
                .findSnapshotAnchor(ownerId, projectId)
                .orElseThrow(this::notFound);
        List<CanvasService.CanvasEntry> canvasEntries = canvas.list(ownerId, projectId);
        List<AgentInstance> agentInstances = agents.list(ownerId, projectId);
        AgentRun activeRun = anchor.activeRunId() == null
                ? null
                : runs.get(ownerId, projectId, anchor.activeRunId());
        List<Task> activeTasks = new java.util.ArrayList<>(tasks.listActiveDirect(ownerId, projectId));
        if (activeRun != null) activeTasks.addAll(tasks.listByRun(ownerId, projectId, activeRun.id()));
        List<Task> unknownTasks = tasks.listUnknown(ownerId, projectId);
        return new ProjectSnapshot(
                project,
                canvasEntries,
                agentInstances,
                activeRun,
                activeTasks,
                unknownTasks,
                anchor.eventSequence());
    }

    /** 项目不存在或不属于当前用户时统一返回 404。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "项目不存在",
                "项目不存在或当前用户无权访问。",
                false);
    }

    /**
     * 与项目本地事件序号水位绑定的完整恢复负载。
     *
     * @param project 项目设置及事件序号
     * @param canvas 当前画布展示项
     * @param agents 项目 Agent 卡片及固定输入
     * @param activeRun 当前占用项目槽位的 Run；无活动 Run 时为空
     * @param activeTasks 活动 Run 与用户直接媒体任务
     * @param unknownTasks 需要核对或人工处理的未决任务
     * @param snapshotSeq 与上述所有数据来自同一 MVCC 快照的事件水位
     */
    public record ProjectSnapshot(
            Project project,
            List<CanvasService.CanvasEntry> canvas,
            List<AgentInstance> agents,
            AgentRun activeRun,
            List<Task> activeTasks,
            List<Task> unknownTasks,
            long snapshotSeq) {}
}
