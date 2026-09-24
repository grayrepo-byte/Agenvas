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

/** 实现项目所有者边界、乐观并发更新、活动 Run 槽位和键集分页。 */
@Service
public class ProjectService {

    /** 项目列表默认分页大小。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 项目列表单页最大记录数。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 执行所有者范围内的项目读取、行锁及条件更新。 */
    private final ProjectRepository projects;
    /** 为创建、槽位变化和归档时间提供统一时钟。 */
    private final Clock clock;

    /** 注入项目仓储和可控时间源。
     * @param projects 执行所有者范围查询与版本条件更新
     * @param clock 提供创建、修改和归档时间
     */
    public ProjectService(ProjectRepository projects, Clock clock) {
        this.projects = projects;
        this.clock = clock;
    }

    /** 创建归属于认证用户的活动项目，并初始化空的 Run 槽位和事件序号。 */
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

    /** 只读取当前用户拥有的项目；越权 ID 与不存在返回相同的 404。 */
    @Transactional(readOnly = true)
    public Project get(UUID ownerId, UUID projectId) {
        return projects.findById(ownerId, projectId).orElseThrow(this::notFound);
    }

    /** 读取并要求项目仍处于 ACTIVE，供创建 Run 等新操作使用。 */
    @Transactional(readOnly = true)
    public Project requireActiveProject(UUID ownerId, UUID projectId) {
        Project project = get(ownerId, projectId);
        requireActive(project);
        return project;
    }

    /** 创建 Run 前锁定项目行；归档项目或已有活动 Run 时拒绝占槽。 */
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

    /** Run 行已插入且项目锁仍有效时，将空槽条件更新为该 Run。 */
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

    /** 仅当槽位仍指向指定 Run 时释放；槽位已变化说明存在并发冲突。 */
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

    /** 在用户所有者范围内按创建时间与 ID 键集分页，游标不暴露原始字段结构。 */
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

    /** 只修改调用方读取版本对应的项目字段；至少提供名称或画幅之一。 */
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

    /** 以预期版本归档项目；同一归档命令重放可返回原状态，其他旧版本冲突。 */
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

    /** 归档项目仍可读取，但不能修改设置或启动新 Run。 */
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

    /** 去除项目名称首尾空白并限制为 1 至 120 字符。 */
    private String validateName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 120) {
            throw validation("项目名称必须为 1 至 120 个字符。");
        }
        return normalized;
    }

    /** 解码秒、纳秒和项目 ID 游标；格式损坏时返回稳定参数错误。 */
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

    /** 生成由创建时间和项目 ID 共同确定的下一页游标。 */
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

    /** 隐藏其他用户项目的存在性。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "项目不存在",
                "项目不存在或当前用户无权访问。",
                false);
    }

    /** 乐观更新未命中预期版本时返回稳定冲突码。 */
    private ApiProblemException versionConflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "VERSION_CONFLICT",
                "项目已更新",
                "项目已被其他请求修改，请读取最新版本后重试。",
                false);
    }

    /** 将请求字段和游标校验失败映射为 HTTP 400。 */
    private ApiProblemException validation(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求参数无效",
                detail,
                false);
    }

    /**
     * 项目列表的一页及下一页不透明游标。
     *
     * @param items 当前页项目，最多为请求 limit 条
     * @param nextCursor 有后续记录时提供的游标，否则为空
     */
    public record ProjectPage(List<Project> items, String nextCursor) {}

    /** 键集游标边界；时间相同时以 UUID 稳定排序并继续分页。
     * @param createdAt 上一页最后项目的创建时间
     * @param id 上一页最后项目 UUID
     */
    private record ProjectCursor(Instant createdAt, UUID id) {}
}
