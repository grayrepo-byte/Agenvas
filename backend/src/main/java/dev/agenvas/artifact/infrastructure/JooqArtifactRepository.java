package dev.agenvas.artifact.infrastructure;

import static dev.agenvas.db.Tables.ARTIFACT;
import static dev.agenvas.db.Tables.ARTIFACT_VERSION;
import static dev.agenvas.db.Tables.ARTIFACT_VERSION_REFERENCE;
import static dev.agenvas.db.Tables.IDEMPOTENCY_RECORD;
import static dev.agenvas.db.Tables.MEDIA_DRAFT;
import static dev.agenvas.db.Tables.PROJECT;

import dev.agenvas.artifact.application.ArtifactRepository;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.db.tables.records.ArtifactRecord;
import dev.agenvas.db.tables.records.ArtifactVersionRecord;
import dev.agenvas.db.tables.records.MediaDraftRecord;
import dev.agenvas.shared.idempotency.IdempotencyState;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 产物仓储；版本行只追加，当前版本选择通过条件更新指针完成。 */
@Repository
public class JooqArtifactRepository implements ArtifactRepository {

    /** 执行 jOOQ 查询和版本条件更新。 */
    private final DSLContext dsl;
    /** 将正文和类型化引用序列化为 JSONB。 */
    private final ObjectMapper objectMapper;

    /** 初始化正文及产物行的 JSON 映射依赖。 */
    public JooqArtifactRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    /** 先占用用户、作用域和键的唯一槽位；并发请求中只有一个请求可首次创建。 */
    @Override
    public boolean reserveCreateKey(UUID ownerId, String scope, String key,
            String requestHash, Instant expiresAt, Instant now) {
        return dsl.insertInto(IDEMPOTENCY_RECORD)
                .set(IDEMPOTENCY_RECORD.PRINCIPAL_ID, ownerId)
                .set(IDEMPOTENCY_RECORD.SCOPE, scope)
                .set(IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY, key)
                .set(IDEMPOTENCY_RECORD.REQUEST_HASH, requestHash)
                .set(IDEMPOTENCY_RECORD.STATE, IdempotencyState.IN_PROGRESS.name())
                .set(IDEMPOTENCY_RECORD.RESOURCE_ID, (UUID) null)
                .set(IDEMPOTENCY_RECORD.RESPONSE_JSON, (JSONB) null)
                .set(IDEMPOTENCY_RECORD.EXPIRES_AT, utc(expiresAt))
                .set(IDEMPOTENCY_RECORD.CREATED_AT, utc(now))
                .set(IDEMPOTENCY_RECORD.UPDATED_AT, utc(now))
                .onConflict(IDEMPOTENCY_RECORD.PRINCIPAL_ID, IDEMPOTENCY_RECORD.SCOPE,
                        IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY)
                .doNothing()
                .execute() == 1;
    }

    /** 读取幂等请求摘要、状态及首次创建时固定的响应快照。 */
    @Override
    public Optional<CreateKey> findCreateKey(UUID ownerId, String scope, String key) {
        return dsl.select(IDEMPOTENCY_RECORD.REQUEST_HASH, IDEMPOTENCY_RECORD.STATE,
                        IDEMPOTENCY_RECORD.RESOURCE_ID, IDEMPOTENCY_RECORD.RESPONSE_JSON)
                .from(IDEMPOTENCY_RECORD)
                .where(IDEMPOTENCY_RECORD.PRINCIPAL_ID.eq(ownerId))
                .and(IDEMPOTENCY_RECORD.SCOPE.eq(scope))
                .and(IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(row -> new CreateKey(row.value1(), IdempotencyState.valueOf(row.value2()), row.value3(),
                        row.value4() == null ? null : row.value4().data()));
    }

    /** 仅原摘要匹配且仍 IN_PROGRESS 时完成创建键，固定资源 ID 和原响应。 */
    @Override
    public boolean completeCreateKey(UUID ownerId, String scope, String key,
            String requestHash, UUID artifactId, String responseJson, Instant now) {
        return dsl.update(IDEMPOTENCY_RECORD)
                .set(IDEMPOTENCY_RECORD.STATE, IdempotencyState.COMPLETED.name())
                .set(IDEMPOTENCY_RECORD.RESOURCE_ID, artifactId)
                .set(IDEMPOTENCY_RECORD.RESPONSE_JSON,
                        responseJson == null ? null : JSONB.valueOf(responseJson))
                .set(IDEMPOTENCY_RECORD.UPDATED_AT, utc(now))
                .where(IDEMPOTENCY_RECORD.PRINCIPAL_ID.eq(ownerId))
                .and(IDEMPOTENCY_RECORD.SCOPE.eq(scope))
                .and(IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY.eq(key))
                .and(IDEMPOTENCY_RECORD.REQUEST_HASH.eq(requestHash))
                .and(IDEMPOTENCY_RECORD.STATE.eq(IdempotencyState.IN_PROGRESS.name()))
                .execute() == 1;
    }

    /** 插入尚未关联当前版本的稳定产物身份。 */
    @Override
    public void createArtifact(Artifact artifact) {
        dsl.insertInto(ARTIFACT)
                .set(ARTIFACT.ID, artifact.id())
                .set(ARTIFACT.PROJECT_ID, artifact.projectId())
                .set(ARTIFACT.KIND, artifact.kind().name())
                .set(ARTIFACT.TITLE, artifact.title())
                .set(ARTIFACT.RESOURCE_DEFAULT_VERSION_ID, (UUID) null)
                .set(ARTIFACT.ARCHIVED_AT, (OffsetDateTime) null)
                .set(ARTIFACT.VERSION, artifact.version())
                .set(ARTIFACT.CREATED_AT, utc(artifact.createdAt()))
                .set(ARTIFACT.UPDATED_AT, utc(artifact.updatedAt()))
                .execute();
    }

    @Override
    public void createMediaDraft(UUID projectId, UUID canvasItemId, String prompt,
            MediaDraft.DisplayMode displayMode, Instant now) {
        dsl.insertInto(MEDIA_DRAFT)
                .set(MEDIA_DRAFT.PROJECT_ID, projectId)
                .set(MEDIA_DRAFT.CANVAS_ITEM_ID, canvasItemId)
                .set(MEDIA_DRAFT.PROMPT, prompt)
                .set(MEDIA_DRAFT.DISPLAY_MODE, displayMode.name())
                .set(MEDIA_DRAFT.VERSION, 0L)
                .set(MEDIA_DRAFT.CREATED_AT, utc(now))
                .set(MEDIA_DRAFT.UPDATED_AT, utc(now))
                .execute();
    }

    @Override
    public Optional<MediaDraft> findMediaDraft(UUID projectId, UUID canvasItemId) {
        return dsl.selectFrom(MEDIA_DRAFT)
                .where(MEDIA_DRAFT.PROJECT_ID.eq(projectId))
                .and(MEDIA_DRAFT.CANVAS_ITEM_ID.eq(canvasItemId))
                .fetchOptional(this::mapMediaDraft);
    }

    /** 草稿编辑不改变卡片展示；只有运行受理或结果选用流程切换展示模式。 */
    @Override
    public boolean updateMediaDraft(MediaDraft draft, long expectedVersion) {
        return dsl.update(MEDIA_DRAFT)
                .set(MEDIA_DRAFT.PROMPT, draft.prompt())
                .set(MEDIA_DRAFT.INPUT_IMAGE_VERSION_ID, draft.inputImageVersionId())
                .set(MEDIA_DRAFT.DURATION_SECONDS, draft.durationSeconds())
                .set(MEDIA_DRAFT.CAPABILITY_ID, draft.capabilityId())
                .set(MEDIA_DRAFT.VERSION, MEDIA_DRAFT.VERSION.plus(1))
                .set(MEDIA_DRAFT.UPDATED_AT, utc(draft.updatedAt()))
                .where(MEDIA_DRAFT.PROJECT_ID.eq(draft.projectId()))
                .and(MEDIA_DRAFT.CANVAS_ITEM_ID.eq(draft.canvasItemId()))
                .and(MEDIA_DRAFT.VERSION.eq(expectedVersion))
                .execute() == 1;
    }

    @Override
    public void setMediaDraftDisplayMode(UUID projectId, UUID canvasItemId,
            MediaDraft.DisplayMode mode, Instant now) {
        int changed = dsl.update(MEDIA_DRAFT)
                .set(MEDIA_DRAFT.DISPLAY_MODE, mode.name())
                .set(MEDIA_DRAFT.UPDATED_AT, utc(now))
                .where(MEDIA_DRAFT.PROJECT_ID.eq(projectId))
                .and(MEDIA_DRAFT.CANVAS_ITEM_ID.eq(canvasItemId))
                .execute();
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
        var artifact = ARTIFACT.as("a");
        var owner = PROJECT.as("p");
        var query = dsl.select(artifact.ID, artifact.PROJECT_ID, artifact.KIND, artifact.TITLE,
                        artifact.RESOURCE_DEFAULT_VERSION_ID, artifact.ARCHIVED_AT, artifact.VERSION,
                        artifact.CREATED_AT, artifact.UPDATED_AT)
                .from(artifact)
                .join(owner).on(owner.ID.eq(artifact.PROJECT_ID))
                .where(artifact.ID.eq(artifactId))
                .and(artifact.PROJECT_ID.eq(projectId))
                .and(owner.OWNER_ID.eq(ownerId));
        if (forUpdate) {
            // for update of a：只锁产物行；所有者项目行由事件序号锁独占，不在此加锁。
            query.forUpdate().of(artifact);
        }
        return query.fetchOptional(row -> new Artifact(
                row.value1(),
                row.value2(),
                Artifact.Kind.valueOf(row.value3()),
                row.value4(),
                row.value5(),
                row.value6() == null ? null : row.value6().toInstant(),
                row.value7(),
                row.value8().toInstant(),
                row.value9().toInstant()));
    }

    /** 插入不可变正文行及其关系表引用；同一事务失败时两者一并回滚。 */
    @Override
    public void appendVersion(ArtifactVersion version) {
        dsl.insertInto(ARTIFACT_VERSION)
                .set(ARTIFACT_VERSION.ID, version.id())
                .set(ARTIFACT_VERSION.PROJECT_ID, version.projectId())
                .set(ARTIFACT_VERSION.ARTIFACT_ID, version.artifactId())
                .set(ARTIFACT_VERSION.VERSION_NO, version.versionNo())
                .set(ARTIFACT_VERSION.SCHEMA_VERSION, version.schemaVersion())
                .set(ARTIFACT_VERSION.CONTENT_JSON, JSONB.valueOf(version.content().toString()))
                .set(ARTIFACT_VERSION.INPUT_REFS_JSON,
                        JSONB.valueOf(objectMapper.writeValueAsString(version.inputReferences())))
                .set(ARTIFACT_VERSION.CREATED_BY_KIND, version.createdByKind().name())
                .set(ARTIFACT_VERSION.RUN_ID, version.runId())
                .set(ARTIFACT_VERSION.CREATED_AT, utc(version.createdAt()))
                .execute();
        for (ArtifactVersion.InputReference reference : version.inputReferences()) {
            dsl.insertInto(ARTIFACT_VERSION_REFERENCE)
                    .set(ARTIFACT_VERSION_REFERENCE.SOURCE_VERSION_ID, version.id())
                    .set(ARTIFACT_VERSION_REFERENCE.PROJECT_ID, version.projectId())
                    .set(ARTIFACT_VERSION_REFERENCE.TARGET_VERSION_ID, reference.versionId())
                    .set(ARTIFACT_VERSION_REFERENCE.REFERENCE_ROLE, reference.role())
                    .set(ARTIFACT_VERSION_REFERENCE.REFERENCE_ORDER, reference.order())
                    .execute();
        }
    }

    /** 只在空指针且初始版本号为零时关联首个版本，避免覆盖已有选择。 */
    @Override
    public void setInitialResourceDefaultVersion(UUID artifactId, UUID versionId, Instant updatedAt) {
        int changed = dsl.update(ARTIFACT)
                .set(ARTIFACT.RESOURCE_DEFAULT_VERSION_ID, versionId)
                .set(ARTIFACT.UPDATED_AT, utc(updatedAt))
                .where(ARTIFACT.ID.eq(artifactId))
                .and(ARTIFACT.RESOURCE_DEFAULT_VERSION_ID.isNull())
                .and(ARTIFACT.VERSION.eq(0L))
                .execute();
        if (changed != 1) {
            throw new IllegalStateException("Failed to attach initial ArtifactVersion");
        }
    }

    /** 仅预期版本匹配、产物未归档且目标版本属于该产物时切换当前指针。 */
    @Override
    public boolean setResourceDefaultVersion(
            UUID ownerId,
            UUID projectId,
            UUID artifactId,
            long expectedVersion,
            UUID versionId,
            String title,
            Instant updatedAt) {
        var artifact = ARTIFACT.as("a");
        var owner = PROJECT.as("p");
        return dsl.update(artifact)
                .set(artifact.RESOURCE_DEFAULT_VERSION_ID, versionId)
                .set(artifact.TITLE, title)
                .set(artifact.UPDATED_AT, utc(updatedAt))
                .set(artifact.VERSION, artifact.VERSION.plus(1))
                .from(owner)
                .where(artifact.ID.eq(artifactId))
                .and(artifact.PROJECT_ID.eq(projectId))
                .and(owner.ID.eq(artifact.PROJECT_ID))
                .and(owner.OWNER_ID.eq(ownerId))
                .and(artifact.VERSION.eq(expectedVersion))
                .and(artifact.ARCHIVED_AT.isNull())
                .and(DSL.exists(dsl.selectOne()
                        .from(ARTIFACT_VERSION)
                        .where(ARTIFACT_VERSION.ID.eq(versionId))
                        .and(ARTIFACT_VERSION.ARTIFACT_ID.eq(artifact.ID))
                        .and(ARTIFACT_VERSION.PROJECT_ID.eq(artifact.PROJECT_ID))))
                .execute() == 1;
    }

    /** 以项目、产物和版本三重键读取正文，并附加其规范化语义引用。 */
    @Override
    public Optional<ArtifactVersion> findVersion(
            UUID projectId, UUID artifactId, UUID versionId) {
        return dsl.selectFrom(ARTIFACT_VERSION)
                .where(ARTIFACT_VERSION.ID.eq(versionId))
                .and(ARTIFACT_VERSION.PROJECT_ID.eq(projectId))
                .and(ARTIFACT_VERSION.ARTIFACT_ID.eq(artifactId))
                .fetchOptional(this::mapBaseVersion)
                .map(this::withReferences);
    }

    /** 由项目内版本 ID 解析其所属产物和类型，不接受跨项目引用。 */
    @Override
    public Optional<VersionTarget> findVersionTarget(UUID projectId, UUID versionId) {
        return dsl.select(ARTIFACT_VERSION.ID, ARTIFACT_VERSION.ARTIFACT_ID, ARTIFACT.KIND)
                .from(ARTIFACT_VERSION)
                .join(ARTIFACT).on(ARTIFACT.ID.eq(ARTIFACT_VERSION.ARTIFACT_ID)
                        .and(ARTIFACT.PROJECT_ID.eq(ARTIFACT_VERSION.PROJECT_ID)))
                .where(ARTIFACT_VERSION.PROJECT_ID.eq(projectId))
                .and(ARTIFACT_VERSION.ID.eq(versionId))
                .fetchOptional(row -> new VersionTarget(row.value1(), row.value2(),
                        Artifact.Kind.valueOf(row.value3())));
    }

    /** 单次查询解析多个版本目标，避免逐个引用产生 N+1 查询。 */
    @Override
    public Map<UUID, VersionTarget> findVersionTargets(UUID projectId, Set<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        List<VersionTarget> targets = dsl
                .select(ARTIFACT_VERSION.ID, ARTIFACT_VERSION.ARTIFACT_ID, ARTIFACT.KIND)
                .from(ARTIFACT_VERSION)
                .join(ARTIFACT).on(ARTIFACT.ID.eq(ARTIFACT_VERSION.ARTIFACT_ID)
                        .and(ARTIFACT.PROJECT_ID.eq(ARTIFACT_VERSION.PROJECT_ID)))
                .where(ARTIFACT_VERSION.PROJECT_ID.eq(projectId))
                .and(ARTIFACT_VERSION.ID.in(versionIds))
                .fetch(row -> new VersionTarget(row.value1(), row.value2(),
                        Artifact.Kind.valueOf(row.value3())));
        Map<UUID, VersionTarget> byId = new LinkedHashMap<>();
        targets.forEach(target -> byId.put(target.versionId(), target));
        return Map.copyOf(byId);
    }

    /** 按版本序号倒序列出一个产物的正文，并分别附加类型化输入引用。 */
    @Override
    public List<ArtifactVersion> listVersions(UUID projectId, UUID artifactId) {
        return dsl.selectFrom(ARTIFACT_VERSION)
                .where(ARTIFACT_VERSION.PROJECT_ID.eq(projectId))
                .and(ARTIFACT_VERSION.ARTIFACT_ID.eq(artifactId))
                .orderBy(ARTIFACT_VERSION.VERSION_NO.desc())
                .fetch(this::mapBaseVersion)
                .stream()
                .map(this::withReferences)
                .toList();
    }

    /** 在项目所有者范围内按创建顺序列出稳定产物身份。 */
    @Override
    public List<Artifact> listProjectArtifacts(UUID ownerId, UUID projectId) {
        return dsl.select(ARTIFACT.fields())
                .from(ARTIFACT)
                .join(PROJECT).on(PROJECT.ID.eq(ARTIFACT.PROJECT_ID))
                .where(ARTIFACT.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .orderBy(ARTIFACT.CREATED_AT, ARTIFACT.ID)
                .fetch(row -> mapArtifact(row.into(ARTIFACT)));
    }

    /** 人工新版本和其他会话版本不再自动继承，避免会话记忆扩大修改权限。 */
    @Override
    public List<Artifact> listSelectedRunOutputs(UUID ownerId, UUID projectId,
            List<UUID> authorizedRunIds, int limit) {
        if (authorizedRunIds.isEmpty()) return List.of();
        return dsl.select(ARTIFACT.fields())
                .from(ARTIFACT)
                .join(PROJECT).on(PROJECT.ID.eq(ARTIFACT.PROJECT_ID))
                .join(ARTIFACT_VERSION).on(ARTIFACT_VERSION.PROJECT_ID.eq(ARTIFACT.PROJECT_ID)
                        .and(ARTIFACT_VERSION.ARTIFACT_ID.eq(ARTIFACT.ID))
                        .and(ARTIFACT_VERSION.ID.eq(ARTIFACT.RESOURCE_DEFAULT_VERSION_ID)))
                .where(ARTIFACT.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .and(ARTIFACT.ARCHIVED_AT.isNull())
                .and(ARTIFACT_VERSION.CREATED_BY_KIND.ne(
                        ArtifactVersion.CreatedByKind.USER.name()))
                .and(ARTIFACT_VERSION.RUN_ID.in(authorizedRunIds))
                .orderBy(ARTIFACT_VERSION.CREATED_AT.desc(), ARTIFACT_VERSION.ID.desc())
                .limit(limit)
                .fetch(row -> mapArtifact(row.into(ARTIFACT)));
    }

    /** 为导出清单按产物和版本顺序读取项目正文；调用方负责白名单脱敏。 */
    @Override
    public List<ArtifactVersion> listProjectVersions(UUID projectId) {
        return dsl.selectFrom(ARTIFACT_VERSION)
                .where(ARTIFACT_VERSION.PROJECT_ID.eq(projectId))
                .orderBy(ARTIFACT_VERSION.ARTIFACT_ID, ARTIFACT_VERSION.VERSION_NO)
                .fetch(this::mapBaseVersion);
    }

    /** 返回该产物当前最大版本序号加一；调用方需先锁定产物行串行化追加。 */
    @Override
    public int nextVersionNo(UUID projectId, UUID artifactId) {
        var next = DSL.coalesce(DSL.max(ARTIFACT_VERSION.VERSION_NO), 0).plus(1);
        return dsl.select(next)
                .from(ARTIFACT_VERSION)
                .where(ARTIFACT_VERSION.PROJECT_ID.eq(projectId))
                .and(ARTIFACT_VERSION.ARTIFACT_ID.eq(artifactId))
                .fetchSingle(next);
    }

    /** 映射版本正文和创建来源，不加载单独规范化的引用关系。 */
    private ArtifactVersion mapBaseVersion(ArtifactVersionRecord row) {
        JsonNode content = objectMapper.readTree(row.getContentJson().data());
        return new ArtifactVersion(
                row.getId(),
                row.getProjectId(),
                row.getArtifactId(),
                row.getVersionNo(),
                row.getSchemaVersion(),
                content,
                List.of(),
                ArtifactVersion.CreatedByKind.valueOf(row.getCreatedByKind()),
                row.getRunId(),
                row.getCreatedAt().toInstant());
    }

    /** 加载来源版本的语义引用并按原顺序组装不可变版本对象。 */
    private ArtifactVersion withReferences(ArtifactVersion version) {
        List<ArtifactVersion.InputReference> references = dsl
                .select(ARTIFACT_VERSION_REFERENCE.TARGET_VERSION_ID,
                        ARTIFACT_VERSION_REFERENCE.REFERENCE_ROLE,
                        ARTIFACT_VERSION_REFERENCE.REFERENCE_ORDER,
                        ARTIFACT.KIND)
                .from(ARTIFACT_VERSION_REFERENCE)
                .join(ARTIFACT_VERSION).on(ARTIFACT_VERSION.ID.eq(
                                ARTIFACT_VERSION_REFERENCE.TARGET_VERSION_ID)
                        .and(ARTIFACT_VERSION.PROJECT_ID.eq(
                                ARTIFACT_VERSION_REFERENCE.PROJECT_ID)))
                .join(ARTIFACT).on(ARTIFACT.ID.eq(ARTIFACT_VERSION.ARTIFACT_ID)
                        .and(ARTIFACT.PROJECT_ID.eq(ARTIFACT_VERSION.PROJECT_ID)))
                .where(ARTIFACT_VERSION_REFERENCE.SOURCE_VERSION_ID.eq(version.id()))
                .orderBy(ARTIFACT_VERSION_REFERENCE.REFERENCE_ROLE,
                        ARTIFACT_VERSION_REFERENCE.REFERENCE_ORDER)
                .fetch(row -> new ArtifactVersion.InputReference(
                        row.value1(),
                        row.value2(),
                        row.value3(),
                        Artifact.Kind.valueOf(row.value4())));
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

    /** 映射稳定产物身份及资源默认版本指针，不读取版本正文。 */
    private Artifact mapArtifact(ArtifactRecord row) {
        return new Artifact(
                row.getId(),
                row.getProjectId(),
                Artifact.Kind.valueOf(row.getKind()),
                row.getTitle(),
                row.getResourceDefaultVersionId(),
                row.getArchivedAt() == null ? null : row.getArchivedAt().toInstant(),
                row.getVersion(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    /** 映射草稿正文、可空生成参数及独立乐观版本。 */
    private MediaDraft mapMediaDraft(MediaDraftRecord row) {
        return new MediaDraft(
                row.getProjectId(),
                row.getCanvasItemId(),
                row.getPrompt(),
                row.getInputImageVersionId(),
                row.getDurationSeconds(),
                row.getCapabilityId(),
                MediaDraft.DisplayMode.valueOf(row.getDisplayMode()),
                row.getVersion(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    /** 将业务时间转换成 UTC 偏移值，避免依赖数据库会话时区。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
