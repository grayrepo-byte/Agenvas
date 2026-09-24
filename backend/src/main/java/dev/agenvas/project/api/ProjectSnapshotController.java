package dev.agenvas.project.api;

import dev.agenvas.agent.api.AgentInstanceController.AgentResponse;
import dev.agenvas.canvas.api.CanvasController.CanvasResponse;
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

/** REST boundary for a refresh-safe project snapshot and event replay waterline. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/snapshot")
public class ProjectSnapshotController {

    private final ProjectSnapshotService snapshots;

    public ProjectSnapshotController(ProjectSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    /** Returns all currently needed workspace entities from one repeatable-read transaction. */
    @GetMapping
    public ProjectSnapshotResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        ProjectSnapshotService.ProjectSnapshot snapshot =
                snapshots.snapshot(principal.userId(), projectId);
        return new ProjectSnapshotResponse(
                ProjectResponse.from(snapshot.project()),
                CanvasResponse.from(snapshot.canvas()),
                snapshot.agents().stream().map(AgentResponse::from).toList(),
                snapshot.activeRun() == null ? null : RunResponse.from(snapshot.activeRun()),
                snapshot.activeTasks().stream().map(TaskResponse::from).toList(),
                snapshot.unknownTasks().stream().map(TaskResponse::from).toList(),
                snapshot.snapshotSeq());
    }

    /** Public snapshot envelope; null activeRun and an empty task list mean the slot is idle. */
    public record ProjectSnapshotResponse(
            ProjectResponse project,
            CanvasResponse canvas,
            List<AgentResponse> agents,
            RunResponse activeRun,
            List<TaskResponse> activeTasks,
            List<TaskResponse> unknownTasks,
            long snapshotSeq) {}
}
