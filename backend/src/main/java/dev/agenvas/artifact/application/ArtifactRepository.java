package dev.agenvas.artifact.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.MediaDraft;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 产物身份与只追加内容版本的持久化边界。 */
public interface ArtifactRepository {

    /** 在当前事务中预留所有者和项目范围内的手工创建幂等键。 */
    boolean reserveCreateKey(UUID ownerId, String scope, String key, String requestHash,
            Instant expiresAt, Instant now);

    /** PostgreSQL 唯一约束完成并发仲裁后，读取先提交的幂等记录。 */
    Optional<CreateKey> findCreateKey(UUID ownerId, String scope, String key);

    /** 在产物和项目事件事务中将同一幂等键标记为完成。 */
    boolean completeCreateKey(UUID ownerId, String scope, String key,
            String requestHash, UUID artifactId, String responseJson, Instant now);

    /** 先插入稳定产物身份，再追加首个不可变内容版本。 */
    void createArtifact(Artifact artifact);

    /** Initialize editable input for an empty IMAGE or VIDEO Artifact in the creation transaction. */
    void createMediaDraft(UUID projectId, UUID artifactId, String prompt,
            MediaDraft.DisplayMode displayMode, Instant now);

    /** Read the working draft of an authorized media Artifact. */
    Optional<MediaDraft> findMediaDraft(UUID projectId, UUID artifactId);

    /** Replace draft fields under its independent optimistic version. */
    boolean updateMediaDraft(MediaDraft draft, long expectedVersion);

    /** Change only the card face, without invalidating the saved input version. */
    void setMediaDraftDisplayMode(UUID projectId, UUID artifactId,
            MediaDraft.DisplayMode mode, Instant now);

    /** 锁定所有者范围内的产物行，为其安全分配递增版本号。 */
    Optional<Artifact> findForUpdate(UUID ownerId, UUID projectId, UUID artifactId);

    /** 仅在所有者作用域内读取产物，避免暴露其他用户资源。 */
    Optional<Artifact> find(UUID ownerId, UUID projectId, UUID artifactId);

    /** 追加不可变内容版本及规范化后的语义引用。 */
    void appendVersion(ArtifactVersion version);

    /** 设置新产物的首个当前版本，不递增产物配置版本。 */
    void setInitialCurrentVersion(UUID artifactId, UUID versionId, Instant updatedAt);

    /** 以乐观锁选择当前内容版本，并可同时修改产物标题。 */
    boolean selectVersion(
            UUID ownerId,
            UUID projectId,
            UUID artifactId,
            long expectedVersion,
            UUID versionId,
            String title,
            Instant updatedAt);

    /** 仅当版本归属指定产物及项目时返回该版本。 */
    Optional<ArtifactVersion> findVersion(
            UUID projectId, UUID artifactId, UUID versionId);

    /** 在项目作用域内读取版本，用于校验语义引用。 */
    Optional<VersionTarget> findVersionTarget(UUID projectId, UUID versionId);

    /** 在单个项目范围内批量解析引用目标。 */
    Map<UUID, VersionTarget> findVersionTargets(UUID projectId, Set<UUID> versionIds);

    /** 按版本号倒序列出不可变历史。 */
    List<ArtifactVersion> listVersions(UUID projectId, UUID artifactId);

    /** 列出所有者授权项目的产物身份，供一致性清单构建使用。 */
    List<Artifact> listProjectArtifacts(UUID ownerId, UUID projectId);

    /** 返回已授权历史 Run 仍被当前选用的非人工输出，供同会话下一轮冻结精确输入。 */
    List<Artifact> listSelectedRunOutputs(UUID ownerId, UUID projectId,
            List<UUID> authorizedRunIds, int limit);

    /** 一次读取项目全部不可变版本，避免逐版本查询引用产生 N+1。 */
    List<ArtifactVersion> listProjectVersions(UUID projectId);

    /** 持有产物行锁时分配下一个递增内容版本号。 */
    int nextVersionNo(UUID projectId, UUID artifactId);

    /** 引用校验所需的最小投影，避免加载版本正文。
     * @param versionId 被引用的不可变版本
     * @param artifactId 该版本归属的产物
     * @param kind 产物类型，用于检查调用方允许引用的媒体类别
     */
    record VersionTarget(UUID versionId, UUID artifactId, Artifact.Kind kind) {}

    /** 手工创建产物的幂等重放记录。
     * @param requestHash 原请求规范化后的摘要
     * @param state 幂等记录状态，用于区分处理中和已完成
     * @param artifactId 首次请求创建的产物
     * @param responseJson 首次创建时持久化的响应，保证重放结果一致
     */
    record CreateKey(String requestHash, String state, UUID artifactId, String responseJson) {}
}
