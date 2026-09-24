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

/** Assembles project recovery state and its event waterline from one MVCC snapshot. */
@Service
public class ProjectSnapshotService {

    private final ProjectService projects;
    private final ProjectRepository projectRepository;
    private final CanvasService canvas;
    private final AgentInstanceService agents;
    private final AgentRunService runs;
    private final TaskService tasks;

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
     * Reads every component under PostgreSQL REPEATABLE READ so snapshotSeq can safely become the
     * exclusive cursor for subsequent event replay.
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
        List<Task> activeTasks = activeRun == null
                ? List.of()
                : tasks.listByRun(ownerId, projectId, activeRun.id());
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

    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "项目不存在",
                "项目不存在或当前用户无权访问。",
                false);
    }

    /** Complete project recovery payload tied to one project-local event sequence. */
    public record ProjectSnapshot(
            Project project,
            List<CanvasService.CanvasEntry> canvas,
            List<AgentInstance> agents,
            AgentRun activeRun,
            List<Task> activeTasks,
            List<Task> unknownTasks,
            long snapshotSeq) {}
}
