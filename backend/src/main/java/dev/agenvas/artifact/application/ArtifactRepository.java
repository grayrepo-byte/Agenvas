package dev.agenvas.artifact.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence boundary for stable artifacts and append-only content revisions. */
public interface ArtifactRepository {

    /** Reserves one owner/project-scoped manual creation key in the current transaction. */
    boolean reserveCreateKey(UUID ownerId, String scope, String key, String requestHash,
            Instant expiresAt, Instant now);

    /** Reads a competing committed creation key after PostgreSQL uniqueness arbitration. */
    Optional<CreateKey> findCreateKey(UUID ownerId, String scope, String key);

    /** Completes the same key in the artifact/event transaction. */
    boolean completeCreateKey(UUID ownerId, String scope, String key,
            String requestHash, UUID artifactId, String responseJson, Instant now);

    /** Inserts the stable identity before its initial version is appended. */
    void createArtifact(Artifact artifact);

    /** Locks one owner-scoped artifact so revision numbers can be allocated safely. */
    Optional<Artifact> findForUpdate(UUID ownerId, UUID projectId, UUID artifactId);

    /** Reads one owner-scoped artifact without exposing foreign resources. */
    Optional<Artifact> find(UUID ownerId, UUID projectId, UUID artifactId);

    /** Appends an immutable version and its normalized semantic references. */
    void appendVersion(ArtifactVersion version);

    /** Sets the first current version without changing the new artifact's optimistic version. */
    void setInitialCurrentVersion(UUID artifactId, UUID versionId, Instant updatedAt);

    /** Selects a version and optionally updates the title using optimistic concurrency. */
    boolean selectVersion(
            UUID ownerId,
            UUID projectId,
            UUID artifactId,
            long expectedVersion,
            UUID versionId,
            String title,
            Instant updatedAt);

    /** Gets one version only when it belongs to the specified artifact and project. */
    Optional<ArtifactVersion> findVersion(
            UUID projectId, UUID artifactId, UUID versionId);

    /** Gets one version in a project for semantic reference validation. */
    Optional<VersionTarget> findVersionTarget(UUID projectId, UUID versionId);

    /** Resolves all requested targets within one project. */
    Map<UUID, VersionTarget> findVersionTargets(UUID projectId, Set<UUID> versionIds);

    /** Lists immutable history newest first. */
    List<ArtifactVersion> listVersions(UUID projectId, UUID artifactId);

    /** Lists every project identity for a consistent, owner-authorized manifest. */
    List<Artifact> listProjectArtifacts(UUID ownerId, UUID projectId);

    /** Lists all immutable project versions without per-version N+1 reference fetches. */
    List<ArtifactVersion> listProjectVersions(UUID projectId);

    /** Allocates the next monotonic content version while the artifact row is locked. */
    int nextVersionNo(UUID projectId, UUID artifactId);

    /** Small projection used to validate the kind and ownership of a reference. */
    record VersionTarget(UUID versionId, UUID artifactId, Artifact.Kind kind) {}

    /** Minimal durable replay projection for manual Artifact creation. */
    record CreateKey(String requestHash, String state, UUID artifactId, String responseJson) {}
}
