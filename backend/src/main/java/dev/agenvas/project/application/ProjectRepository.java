package dev.agenvas.project.application;

import dev.agenvas.project.domain.Project;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 项目持久化边界；外部读取和修改均携带认证所有者作用域。
 */
public interface ProjectRepository {

    /** 插入一个活动项目。 */
    void create(Project project);

    /** 在指定所有者范围内读取项目。 */
    Optional<Project> findById(UUID ownerId, UUID projectId);

    /** 读取项目快照水位和活动 Run 标识，供一致性快照组装使用。 */
    Optional<SnapshotAnchor> findSnapshotAnchor(UUID ownerId, UUID projectId);

    /** 锁定并返回数据库维护的项目活动 Run 槽位。 */
    Optional<RunSlot> lockRunSlot(UUID ownerId, UUID projectId);

    /** 持有项目行锁时将 Run 写入空闲活动槽位。 */
    boolean claimRunSlot(UUID ownerId, UUID projectId, UUID runId, Instant updatedAt);

    /** 仅当槽位仍由预期 Run 占用时才释放。 */
    boolean releaseRunSlot(UUID ownerId, UUID projectId, UUID runId, Instant updatedAt);

    /** 按创建时间倒序返回一页键集分页结果。 */
    List<Project> list(
            UUID ownerId,
            boolean includeArchived,
            Instant beforeCreatedAt,
            UUID beforeId,
            int limit);

    /** 只有预期版本匹配时才更新可编辑字段。 */
    boolean update(
            UUID ownerId,
            UUID projectId,
            long expectedVersion,
            String name,
            Project.AspectRatio aspectRatio,
            Instant updatedAt);

    /** 只有预期版本匹配时才归档活动项目。 */
    boolean archive(
            UUID ownerId, UUID projectId, long expectedVersion, Instant archivedAt);

    /** Run 活动槽仲裁需要的最小锁定投影。
     * @param projectStatus 项目是否仍允许新 Run
     * @param activeRunId 当前占用槽位的 Run；没有活动 Run 时为空
     */
    record RunSlot(Project.Status projectStatus, UUID activeRunId) {}

    /** 项目一致性快照的锚点，用于把业务实体读取绑定到同一数据库快照。
     * @param eventSequence 项目事件水位，SSE 从此序号之后补发
     * @param activeRunId 快照时占用活动槽位的 Run
     */
    record SnapshotAnchor(long eventSequence, UUID activeRunId) {}
}
