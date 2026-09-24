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

/** Authenticated REST boundary for project creation, paging, editing, and archive. */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    /** Creates a project owned by the authenticated administrator. */
    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @AuthenticationPrincipal AdminPrincipal principal,
            @Valid @RequestBody CreateProjectRequest request) {
        Project project = projects.create(principal.userId(), request.name(), request.aspectRatio());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(project));
    }

    /** Lists projects with a bounded opaque keyset cursor. */
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

    /** Reads an owner-scoped project. */
    @GetMapping("/{projectId}")
    public ProjectResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return ProjectResponse.from(projects.get(principal.userId(), projectId));
    }

    /** Updates editable project settings with optimistic concurrency. */
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

    /** Archives the project and blocks future active-only operations. */
    @PostMapping("/{projectId}/archive")
    public ProjectResponse archive(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody ArchiveProjectRequest request) {
        return ProjectResponse.from(
                projects.archive(principal.userId(), projectId, request.expectedVersion()));
    }

    /** Project creation request. */
    public record CreateProjectRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull Project.AspectRatio aspectRatio) {}

    /** Partial project settings update with required optimistic version. */
    public record UpdateProjectRequest(
            @PositiveOrZero long expectedVersion,
            @Size(min = 1, max = 120) String name,
            Project.AspectRatio aspectRatio) {}

    /** Project archive command. */
    public record ArchiveProjectRequest(@PositiveOrZero long expectedVersion) {}

    /** Public project representation. */
    public record ProjectResponse(
            String id,
            String name,
            Project.AspectRatio aspectRatio,
            Project.Status status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt) {

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

    /** Cursor page response. */
    public record ProjectListResponse(List<ProjectResponse> items, String nextCursor) {}
}
