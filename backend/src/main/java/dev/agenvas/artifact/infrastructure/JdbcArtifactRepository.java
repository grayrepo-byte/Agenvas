package dev.agenvas.artifact.infrastructure;

import dev.agenvas.artifact.application.ArtifactRepository;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.MediaDraft;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 产物仓储；版本行只追加，当前版本选择通过条件更新指针完成。 */
@Repository
public class JdbcArtifactRepository implements ArtifactRepository {

    /** 映射稳定产物身份及当前版本指针，不读取版本正文。 */
    private static final RowMapper<Artifact> ARTIFACT_MAPPER = (resultSet, rowNumber) ->
            new Artifact(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("project_id", UUID.class),
                    Artifact.Kind.valueOf(resultSet.getString("kind")),
                    resultSet.getString("title"),
                    resultSet.getObject("current_version_id", UUID.class),
                    Optional.ofNullable(resultSet.getObject("archived_at", OffsetDateTime.class))
                            .map(OffsetDateTime::toInstant)
                            .orElse(null),
                    resultSet.getLong("version"),
                    resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                    resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());

    /** 执行参数化 SQL 和版本条件更新。 */
    private final JdbcClient jdbcClient;
    /** 将正文和类型化引用序列化为 JSONB。 */
    private final ObjectMapper objectMapper;

    /** 初始化正文及产物行的 JSON 映射依赖。 */
    public JdbcArtifactRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    /** 先占用用户、作用域和键的唯一槽位；并发请求中只有一个请求可首次创建。 */
    @Override
    public boolean reserveCreateKey(UUID ownerId, String scope, String key,
            String requestHash, Instant expiresAt, Instant now) {
        return jdbcClient.sql("""
                        insert into idempotency_record (
                            principal_id, scope, idempotency_key, request_hash, state,
                            resource_id, response_json, expires_at, created_at, updated_at
                        ) values (
                            :ownerId, :scope, :key, :requestHash, 'IN_PROGRESS',
                            null, null, :expiresAt, :now, :now
                        ) on conflict (principal_id, scope, idempotency_key) do nothing
                        """)
                .param("ownerId", ownerId).param("scope", scope).param("key", key)
                .param("requestHash", requestHash).param("expiresAt", utc(expiresAt))
                .param("now", utc(now)).update() == 1;
    }

    /** 读取幂等请求摘要、状态及首次创建时固定的响应快照。 */
    @Override
    public Optional<CreateKey> findCreateKey(UUID ownerId, String scope, String key) {
        return jdbcClient.sql("""
                        select request_hash, state, resource_id,
                               response_json::text as response_json
                        from idempotency_record
                        where principal_id = :ownerId and scope = :scope and idempotency_key = :key
                        """)
                .param("ownerId", ownerId).param("scope", scope).param("key", key)
                .query((rs, row) -> new CreateKey(rs.getString("request_hash"),
                        rs.getString("state"), rs.getObject("resource_id", UUID.class),
                        rs.getString("response_json")))
                .optional();
    }

    /** 仅原摘要匹配且仍 IN_PROGRESS 时完成创建键，固定资源 ID 和原响应。 */
    @Override
    public boolean completeCreateKey(UUID ownerId, String scope, String key,
            String requestHash, UUID artifactId, String responseJson, Instant now) {
        return jdbcClient.sql("""
                        update idempotency_record
                        set state = 'COMPLETED', resource_id = :artifactId,
                            response_json = cast(:response as jsonb), updated_at = :now
                        where principal_id = :ownerId and scope = :scope
                          and idempotency_key = :key and request_hash = :requestHash
                          and state = 'IN_PROGRESS'
                        """)
                .param("artifactId", artifactId)
                .param("response", responseJson)
                .param("now", utc(now)).param("ownerId", ownerId)
                .param("scope", scope).param("key", key).param("requestHash", requestHash)
                .update() == 1;
    }

    /** 插入尚未关联当前版本的稳定产物身份。 */
    @Override
    public void createArtifact(Artifact artifact) {
        jdbcClient.sql("""
                        insert into artifact (
                            id, project_id, kind, title, current_version_id, archived_at,
                            version, created_at, updated_at
                        ) values (
                            :id, :projectId, :kind, :title, null, null,
                            :version, :createdAt, :updatedAt
                        )
                        """)
                .param("id", artifact.id())
                .param("projectId", artifact.projectId())
                .param("kind", artifact.kind().name())
                .param("title", artifact.title())
                .param("version", artifact.version())
                .param("createdAt", utc(artifact.createdAt()))
                .param("updatedAt", utc(artifact.updatedAt()))
                .update();
    }

    @Override
    public void createMediaDraft(UUID projectId, UUID artifactId, String prompt,
            MediaDraft.DisplayMode displayMode, Instant now) {
        jdbcClient.sql("""
                        insert into media_draft (
                            project_id, artifact_id, prompt, display_mode,
                            version, created_at, updated_at
                        ) values (:projectId, :artifactId, :prompt, :displayMode, 0, :now, :now)
                        """)
                .param("projectId", projectId)
                .param("artifactId", artifactId)
                .param("prompt", prompt)
                .param("displayMode", displayMode.name())
                .param("now", utc(now))
                .update();
    }

    @Override
    public Optional<MediaDraft> findMediaDraft(UUID projectId, UUID artifactId) {
        return jdbcClient.sql("""
                        select project_id, artifact_id, prompt, input_image_version_id,
                               duration_seconds, capability_id, display_mode,
                               version, created_at, updated_at
                        from media_draft
                        where project_id = :projectId and artifact_id = :artifactId
                        """)
                .param("projectId", projectId)
                .param("artifactId", artifactId)
                .query((rs, row) -> new MediaDraft(
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("artifact_id", UUID.class),
                        rs.getString("prompt"),
                        rs.getObject("input_image_version_id", UUID.class),
                        rs.getObject("duration_seconds", Integer.class),
                        rs.getObject("capability_id", UUID.class),
                        MediaDraft.DisplayMode.valueOf(rs.getString("display_mode")),
                        rs.getLong("version"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** 草稿编辑不改变卡片展示；只有运行受理或结果选用流程切换展示模式。 */
    @Override
    public boolean updateMediaDraft(MediaDraft draft, long expectedVersion) {
        return jdbcClient.sql("""
                        update media_draft
                        set prompt = :prompt,
                            input_image_version_id = :inputImageVersionId,
                            duration_seconds = :durationSeconds,
                            capability_id = :capabilityId,
                            version = version + 1, updated_at = :updatedAt
                        where project_id = :projectId and artifact_id = :artifactId
                          and version = :expectedVersion
                        """)
                .param("prompt", draft.prompt())
                .param("inputImageVersionId", draft.inputImageVersionId(), java.sql.Types.OTHER)
                .param("durationSeconds", draft.durationSeconds(), java.sql.Types.INTEGER)
                .param("capabilityId", draft.capabilityId(), java.sql.Types.OTHER)
                .param("updatedAt", utc(draft.updatedAt()))
                .param("projectId", draft.projectId())
                .param("artifactId", draft.artifactId())
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    @Override
    public void setMediaDraftDisplayMode(UUID projectId, UUID artifactId,
            MediaDraft.DisplayMode mode, Instant now) {
        int changed = jdbcClient.sql("""
                update media_draft set display_mode=:mode, updated_at=:now
                where project_id=:projectId and artifact_id=:artifactId
                """)
                .param("mode", mode.name()).param("now", utc(now))
                .param("projectId", projectId).param("artifactId", artifactId).update();
        if (changed != 1) throw new IllegalStateException("Media draft missing");
    }

    /** 锁定指定所有者项目中的产物行，供追加版本与选择 CAS 使用。 */
    @Override
    public Optional<Artifact> findForUpdate(UUID ownerId, UUID projectId, UUID artifactId) {
        return findArtifact(ownerId, projectId, artifactId, true);
    }

    /** 在所有者、项目和产物 ID 三重范围内读取稳定身份。 */
    @Override
    public Optional<Artifact> find(UUID ownerId, UUID projectId, UUID artifactId) {
        return findArtifact(ownerId, projectId, artifactId, false);
    }

    /** 共享读写查询；只有调用方请求写操作时才锁定产物行。 */
    private Optional<Artifact> findArtifact(
            UUID ownerId, UUID projectId, UUID artifactId, boolean forUpdate) {
        String lockClause = forUpdate ? " for update of a" : "";
        return jdbcClient.sql("""
                        select a.id, a.project_id, a.kind, a.title, a.current_version_id,
                               a.archived_at, a.version, a.created_at, a.updated_at
                        from artifact a
                        join project p on p.id = a.project_id
                        where a.id = :artifactId and a.project_id = :projectId
                          and p.owner_id = :ownerId
                        """ + lockClause)
                .param("artifactId", artifactId)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .query(ARTIFACT_MAPPER)
                .optional();
    }

    /** 插入不可变正文行及其关系表引用；同一事务失败时两者一并回滚。 */
    @Override
    public void appendVersion(ArtifactVersion version) {
        jdbcClient.sql("""
                        insert into artifact_version (
                            id, project_id, artifact_id, version_no, schema_version,
                            content_json, input_refs_json, created_by_kind, run_id, created_at
                        ) values (
                            :id, :projectId, :artifactId, :versionNo, :schemaVersion,
                            cast(:contentJson as jsonb), cast(:inputRefsJson as jsonb),
                            :createdByKind, :runId, :createdAt
                        )
                        """)
                .param("id", version.id())
                .param("projectId", version.projectId())
                .param("artifactId", version.artifactId())
                .param("versionNo", version.versionNo())
                .param("schemaVersion", version.schemaVersion())
                .param("contentJson", version.content().toString())
                .param("inputRefsJson", objectMapper.writeValueAsString(version.inputReferences()))
                .param("createdByKind", version.createdByKind().name())
                .param("runId", version.runId(), java.sql.Types.OTHER)
                .param("createdAt", utc(version.createdAt()))
                .update();
        for (ArtifactVersion.InputReference reference : version.inputReferences()) {
            jdbcClient.sql("""
                            insert into artifact_version_reference (
                                source_version_id, project_id, target_version_id,
                                reference_role, reference_order
                            ) values (
                                :sourceVersionId, :projectId, :targetVersionId,
                                :referenceRole, :referenceOrder
                            )
                            """)
                    .param("sourceVersionId", version.id())
                    .param("projectId", version.projectId())
                    .param("targetVersionId", reference.versionId())
                    .param("referenceRole", reference.role())
                    .param("referenceOrder", reference.order())
                    .update();
        }
    }

    /** 只在空指针且初始版本号为零时关联首个版本，避免覆盖已有选择。 */
    @Override
    public void setInitialCurrentVersion(UUID artifactId, UUID versionId, Instant updatedAt) {
        int changed = jdbcClient.sql("""
                        update artifact
                        set current_version_id = :versionId, updated_at = :updatedAt
                        where id = :artifactId and current_version_id is null and version = 0
                        """)
                .param("versionId", versionId)
                .param("updatedAt", utc(updatedAt))
                .param("artifactId", artifactId)
                .update();
        if (changed != 1) {
            throw new IllegalStateException("Failed to attach initial ArtifactVersion");
        }
    }

    /** 仅预期版本匹配、产物未归档且目标版本属于该产物时切换当前指针。 */
    @Override
    public boolean selectVersion(
            UUID ownerId,
            UUID projectId,
            UUID artifactId,
            long expectedVersion,
            UUID versionId,
            String title,
            Instant updatedAt) {
        return jdbcClient.sql("""
                        update artifact a
                        set current_version_id = :versionId,
                            title = :title,
                            updated_at = :updatedAt,
                            version = a.version + 1
                        from project p
                        where a.id = :artifactId and a.project_id = :projectId
                          and p.id = a.project_id and p.owner_id = :ownerId
                          and a.version = :expectedVersion and a.archived_at is null
                          and exists (
                              select 1 from artifact_version av
                              where av.id = :versionId
                                and av.artifact_id = a.id
                                and av.project_id = a.project_id
                          )
                        """)
                .param("versionId", versionId)
                .param("title", title)
                .param("updatedAt", utc(updatedAt))
                .param("artifactId", artifactId)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** 以项目、产物和版本三重键读取正文，并附加其规范化语义引用。 */
    @Override
    public Optional<ArtifactVersion> findVersion(
            UUID projectId, UUID artifactId, UUID versionId) {
        return jdbcClient.sql("""
                        select id, project_id, artifact_id, version_no, schema_version,
                               content_json::text as content_json,
                               created_by_kind, run_id, created_at
                        from artifact_version
                        where id = :versionId and project_id = :projectId
                          and artifact_id = :artifactId
                        """)
                .param("versionId", versionId)
                .param("projectId", projectId)
                .param("artifactId", artifactId)
                .query(this::mapBaseVersion)
                .optional()
                .map(this::withReferences);
    }

    /** 由项目内版本 ID 解析其所属产物和类型，不接受跨项目引用。 */
    @Override
    public Optional<VersionTarget> findVersionTarget(UUID projectId, UUID versionId) {
        return jdbcClient.sql("""
                        select av.id as version_id, av.artifact_id, a.kind
                        from artifact_version av
                        join artifact a on a.id = av.artifact_id and a.project_id = av.project_id
                        where av.project_id = :projectId and av.id = :versionId
                        """)
                .param("projectId", projectId)
                .param("versionId", versionId)
                .query((resultSet, rowNumber) -> new VersionTarget(
                        resultSet.getObject("version_id", UUID.class),
                        resultSet.getObject("artifact_id", UUID.class),
                        Artifact.Kind.valueOf(resultSet.getString("kind"))))
                .optional();
    }

    /** 单次查询解析多个版本目标，避免逐个引用产生 N+1 查询。 */
    @Override
    public Map<UUID, VersionTarget> findVersionTargets(UUID projectId, Set<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        List<VersionTarget> targets = jdbcClient.sql("""
                        select av.id as version_id, av.artifact_id, a.kind
                        from artifact_version av
                        join artifact a on a.id = av.artifact_id and a.project_id = av.project_id
                        where av.project_id = :projectId and av.id in (:versionIds)
                        """)
                .param("projectId", projectId)
                .param("versionIds", versionIds)
                .query((resultSet, rowNumber) -> new VersionTarget(
                        resultSet.getObject("version_id", UUID.class),
                        resultSet.getObject("artifact_id", UUID.class),
                        Artifact.Kind.valueOf(resultSet.getString("kind"))))
                .list();
        Map<UUID, VersionTarget> byId = new LinkedHashMap<>();
        targets.forEach(target -> byId.put(target.versionId(), target));
        return Map.copyOf(byId);
    }

    /** 按版本序号倒序列出一个产物的正文，并分别附加类型化输入引用。 */
    @Override
    public List<ArtifactVersion> listVersions(UUID projectId, UUID artifactId) {
        return jdbcClient.sql("""
                        select id, project_id, artifact_id, version_no, schema_version,
                               content_json::text as content_json,
                               created_by_kind, run_id, created_at
                        from artifact_version
                        where project_id = :projectId and artifact_id = :artifactId
                        order by version_no desc
                        """)
                .param("projectId", projectId)
                .param("artifactId", artifactId)
                .query(this::mapBaseVersion)
                .list()
                .stream()
                .map(this::withReferences)
                .toList();
    }

    /** 在项目所有者范围内按创建顺序列出稳定产物身份。 */
    @Override
    public List<Artifact> listProjectArtifacts(UUID ownerId, UUID projectId) {
        return jdbcClient.sql("""
                        select a.id, a.project_id, a.kind, a.title, a.current_version_id,
                               a.archived_at, a.version, a.created_at, a.updated_at
                        from artifact a join project p on p.id = a.project_id
                        where a.project_id = :projectId and p.owner_id = :ownerId
                        order by a.created_at, a.id
                        """)
                .param("ownerId", ownerId).param("projectId", projectId)
                .query(ARTIFACT_MAPPER).list();
    }

    /** 人工新版本和其他会话版本不再自动继承，避免会话记忆扩大修改权限。 */
    @Override
    public List<Artifact> listSelectedRunOutputs(UUID ownerId, UUID projectId,
            List<UUID> authorizedRunIds, int limit) {
        if (authorizedRunIds.isEmpty()) return List.of();
        return jdbcClient.sql("""
                        select a.id, a.project_id, a.kind, a.title, a.current_version_id,
                               a.archived_at, a.version, a.created_at, a.updated_at
                        from artifact a
                        join project p on p.id = a.project_id
                        join artifact_version v on v.project_id = a.project_id
                            and v.artifact_id = a.id and v.id = a.current_version_id
                        where a.project_id = :projectId and p.owner_id = :ownerId
                            and a.archived_at is null and v.created_by_kind <> 'USER'
                            and v.run_id in (:runIds)
                        order by v.created_at desc, v.id desc
                        limit :limit
                        """)
                .param("ownerId", ownerId).param("projectId", projectId)
                .param("runIds", authorizedRunIds).param("limit", limit)
                .query(ARTIFACT_MAPPER).list();
    }

    /** 为导出清单按产物和版本顺序读取项目正文；调用方负责白名单脱敏。 */
    @Override
    public List<ArtifactVersion> listProjectVersions(UUID projectId) {
        return jdbcClient.sql("""
                        select id, project_id, artifact_id, version_no, schema_version,
                               content_json::text as content_json,
                               created_by_kind, run_id, created_at
                        from artifact_version where project_id = :projectId
                        order by artifact_id, version_no
                        """)
                .param("projectId", projectId).query(this::mapBaseVersion).list();
    }

    /** 返回该产物当前最大版本序号加一；调用方需先锁定产物行串行化追加。 */
    @Override
    public int nextVersionNo(UUID projectId, UUID artifactId) {
        return jdbcClient.sql("""
                        select coalesce(max(version_no), 0) + 1
                        from artifact_version
                        where project_id = :projectId and artifact_id = :artifactId
                        """)
                .param("projectId", projectId)
                .param("artifactId", artifactId)
                .query(Integer.class)
                .single();
    }

    /** 映射版本正文和创建来源，不加载单独规范化的引用关系。 */
    private ArtifactVersion mapBaseVersion(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        JsonNode content = objectMapper.readTree(resultSet.getString("content_json"));
        return new ArtifactVersion(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("artifact_id", UUID.class),
                resultSet.getInt("version_no"),
                resultSet.getInt("schema_version"),
                content,
                List.of(),
                ArtifactVersion.CreatedByKind.valueOf(resultSet.getString("created_by_kind")),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    /** 加载来源版本的语义引用并按原顺序组装不可变版本对象。 */
    private ArtifactVersion withReferences(ArtifactVersion version) {
        List<ArtifactVersion.InputReference> references = jdbcClient.sql("""
                        select r.target_version_id, r.reference_role, r.reference_order, a.kind
                        from artifact_version_reference r
                        join artifact_version target on target.id = r.target_version_id
                          and target.project_id = r.project_id
                        join artifact a on a.id = target.artifact_id
                          and a.project_id = target.project_id
                        where r.source_version_id = :sourceVersionId
                        order by r.reference_role, r.reference_order
                        """)
                .param("sourceVersionId", version.id())
                .query((resultSet, rowNumber) -> new ArtifactVersion.InputReference(
                        resultSet.getObject("target_version_id", UUID.class),
                        resultSet.getString("reference_role"),
                        resultSet.getInt("reference_order"),
                        Artifact.Kind.valueOf(resultSet.getString("kind"))))
                .list();
        return new ArtifactVersion(
                version.id(),
                version.projectId(),
                version.artifactId(),
                version.versionNo(),
                version.schemaVersion(),
                version.content(),
                references,
                version.createdByKind(),
                version.runId(),
                version.createdAt());
    }

    /** 将业务时间转换成 JDBC UTC 偏移值，避免依赖数据库会话时区。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
