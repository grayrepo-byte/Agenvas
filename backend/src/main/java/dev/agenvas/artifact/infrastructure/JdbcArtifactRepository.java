package dev.agenvas.artifact.infrastructure;

import dev.agenvas.artifact.application.ArtifactRepository;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
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

/** PostgreSQL implementation that appends content and never mutates version rows. */
@Repository
public class JdbcArtifactRepository implements ArtifactRepository {

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

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcArtifactRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

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
    public Optional<Artifact> findForUpdate(UUID ownerId, UUID projectId, UUID artifactId) {
        return findArtifact(ownerId, projectId, artifactId, true);
    }

    @Override
    public Optional<Artifact> find(UUID ownerId, UUID projectId, UUID artifactId) {
        return findArtifact(ownerId, projectId, artifactId, false);
    }

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

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
