package dev.agenvas.project.application;

import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements owner-scoped project commands, optimistic locking, and keyset pagination. */
@Service
public class ProjectService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProjectRepository projects;
    private final Clock clock;

    public ProjectService(ProjectRepository projects, Clock clock) {
        this.projects = projects;
        this.clock = clock;
    }

    /** Creates one active project owned by the authenticated user. */
    @Transactional
    public Project create(UUID ownerId, String name, Project.AspectRatio aspectRatio) {
        String normalizedName = validateName(name);
        Instant now = clock.instant();
        Project project = new Project(
                UUID.randomUUID(),
                ownerId,
                normalizedName,
                aspectRatio,
                Project.Status.ACTIVE,
                0,
                0,
                now,
                now,
                null);
        projects.create(project);
        return project;
    }

    /** Reads one project without revealing whether another owner has the same id. */
    @Transactional(readOnly = true)
    public Project get(UUID ownerId, UUID projectId) {
        return projects.findById(ownerId, projectId).orElseThrow(this::notFound);
    }

    /** Returns an owned active project for commands such as starting a run. */
    @Transactional(readOnly = true)
    public Project requireActiveProject(UUID ownerId, UUID projectId) {
        Project project = get(ownerId, projectId);
        requireActive(project);
        return project;
    }

    /** Locks the project before Run insertion and verifies that its activity slot is empty. */
    @Transactional
    public void requireAvailableRunSlot(UUID ownerId, UUID projectId) {
        ProjectRepository.RunSlot slot = projects.lockRunSlot(ownerId, projectId)
                .orElseThrow(this::notFound);
        if (slot.projectStatus() == Project.Status.ARCHIVED) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "PROJECT_ARCHIVED",
                    "项目已归档",
                    "归档项目不能启动新的运行。",
                    false);
        }
        if (slot.activeRunId() != null) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ACTIVE_RUN_EXISTS",
                    "项目已有活动运行",
                    "请先完成或取消当前运行，再启动新的运行。",
                    false);
        }
    }

    /** Assigns the already-locked slot after the referenced Run row has been inserted. */
    @Transactional
    public void assignRunSlot(UUID ownerId, UUID projectId, UUID runId) {
        if (!projects.claimRunSlot(ownerId, projectId, runId, clock.instant())) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ACTIVE_RUN_EXISTS",
                    "项目已有活动运行",
                    "请先完成或取消当前运行，再启动新的运行。",
                    false);
        }
    }

    /** Releases the slot only for its current owner Run. */
    @Transactional
    public void releaseRunSlot(UUID ownerId, UUID projectId, UUID runId) {
        if (!projects.releaseRunSlot(ownerId, projectId, runId, clock.instant())) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ACTIVE_RUN_SLOT_CONFLICT",
                    "活动运行槽位已变化",
                    "项目活动运行槽位不再属于当前运行。",
                    false);
        }
    }

    /** Returns an opaque-cursor page within one owner boundary. */
    @Transactional(readOnly = true)
    public ProjectPage list(
            UUID ownerId, boolean includeArchived, String encodedCursor, Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw validation("limit 必须在 1 到 100 之间。");
        }
        ProjectCursor cursor = decodeCursor(encodedCursor);
        List<Project> rows = projects.list(
                ownerId,
                includeArchived,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Project> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? encodeCursor(items.getLast()) : null;
        return new ProjectPage(List.copyOf(items), nextCursor);
    }

    /** Applies editable fields only to the version observed by the caller. */
    @Transactional
    public Project update(
            UUID ownerId,
            UUID projectId,
            long expectedVersion,
            String requestedName,
            Project.AspectRatio requestedAspectRatio) {
        if (requestedName == null && requestedAspectRatio == null) {
            throw validation("至少需要提供一个可修改字段。");
        }
        Project current = get(ownerId, projectId);
        requireActive(current);
        String name = requestedName == null ? current.name() : validateName(requestedName);
        Project.AspectRatio aspectRatio = requestedAspectRatio == null
                ? current.aspectRatio()
                : requestedAspectRatio;
        if (!projects.update(
                ownerId, projectId, expectedVersion, name, aspectRatio, clock.instant())) {
            throw versionConflict();
        }
        return get(ownerId, projectId);
    }

    /** Archives a project and treats an exact command replay as idempotent. */
    @Transactional
    public Project archive(UUID ownerId, UUID projectId, long expectedVersion) {
        Project current = get(ownerId, projectId);
        if (current.status() == Project.Status.ARCHIVED) {
            if (current.version() == expectedVersion + 1) {
                return current;
            }
            throw versionConflict();
        }
        if (!projects.archive(ownerId, projectId, expectedVersion, clock.instant())) {
            throw versionConflict();
        }
        return get(ownerId, projectId);
    }

    private void requireActive(Project project) {
        if (project.status() == Project.Status.ARCHIVED) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "PROJECT_ARCHIVED",
                    "项目已归档",
                    "归档项目不能再修改或启动新的运行。",
                    false);
        }
    }

    private String validateName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 120) {
            throw validation("项目名称必须为 1 至 120 个字符。");
        }
        return normalized;
    }

    private ProjectCursor decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split(":", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("invalid cursor parts");
            }
            Instant createdAt = Instant.ofEpochSecond(
                    Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            return new ProjectCursor(createdAt, UUID.fromString(parts[2]));
        } catch (IllegalArgumentException invalidCursor) {
            throw validation("cursor 无效或已损坏。");
        }
    }

    private String encodeCursor(Project project) {
        String value = project.createdAt().getEpochSecond()
                + ":"
                + project.createdAt().getNano()
                + ":"
                + project.id();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "项目不存在",
                "项目不存在或当前用户无权访问。",
                false);
    }

    private ApiProblemException versionConflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "VERSION_CONFLICT",
                "项目已更新",
                "项目已被其他请求修改，请读取最新版本后重试。",
                false);
    }

    private ApiProblemException validation(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求参数无效",
                detail,
                false);
    }

    /** One keyset page and its next opaque cursor. */
    public record ProjectPage(List<Project> items, String nextCursor) {}

    private record ProjectCursor(Instant createdAt, UUID id) {}
}
