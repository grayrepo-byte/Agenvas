package dev.agenvas.artifact.api;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** REST boundary for creating, revising, reading, and selecting Artifact versions. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/artifacts")
public class ArtifactController {

    private final ArtifactService artifacts;

    public ArtifactController(ArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    /** Creates an Artifact and its first immutable version. */
    @PostMapping
    public ResponseEntity<ArtifactResponse> create(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateArtifactRequest request) {
        ArtifactService.CreateResult result = artifacts.createIdempotent(
                principal.userId(), projectId, request.kind(), request.title(), request.content(),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(ArtifactResponse.from(result.view()));
    }

    /** Reads an owner-scoped Artifact with its selected version. */
    @GetMapping("/{artifactId}")
    public ArtifactResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId) {
        return ArtifactResponse.from(
                artifacts.get(principal.userId(), projectId, artifactId));
    }

    /** Appends and selects a complete immutable revision. */
    @PostMapping("/{artifactId}/revisions")
    public ResponseEntity<ArtifactResponse> revise(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId,
            @Valid @RequestBody ReviseArtifactRequest request) {
        ArtifactService.ArtifactView view = artifacts.revise(
                principal.userId(),
                projectId,
                artifactId,
                request.expectedVersion(),
                request.title(),
                request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(ArtifactResponse.from(view));
    }

    /** Lists immutable history newest first. */
    @GetMapping("/{artifactId}/versions")
    public ArtifactVersionListResponse listVersions(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId) {
        return new ArtifactVersionListResponse(artifacts
                .listVersions(principal.userId(), projectId, artifactId)
                .stream()
                .map(ArtifactVersionResponse::from)
                .toList());
    }

    /** Selects a historical revision without altering its content. */
    @PostMapping("/{artifactId}/select-version")
    public ArtifactResponse selectVersion(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId,
            @Valid @RequestBody SelectArtifactVersionRequest request) {
        return ArtifactResponse.from(artifacts.selectVersion(
                principal.userId(),
                projectId,
                artifactId,
                request.versionId(),
                request.expectedVersion()));
    }

    /** Request for the first complete content revision. */
    public record CreateArtifactRequest(
            @NotNull Artifact.Kind kind,
            @NotBlank @Size(max = 160) String title,
            @NotNull JsonNode content) {}

    /** Request for a complete replacement revision. */
    public record ReviseArtifactRequest(
            @PositiveOrZero long expectedVersion,
            @Size(min = 1, max = 160) String title,
            @NotNull JsonNode content) {}

    /** Optimistic command selecting an existing historical revision. */
    public record SelectArtifactVersionRequest(
            @NotNull UUID versionId, @PositiveOrZero long expectedVersion) {}

    /** Stable Artifact identity plus its currently selected version. */
    public record ArtifactResponse(
            UUID id,
            UUID projectId,
            Artifact.Kind kind,
            String title,
            UUID currentVersionId,
            long version,
            Instant createdAt,
            Instant updatedAt,
            ArtifactVersionResponse currentVersion) {

        public static ArtifactResponse from(ArtifactService.ArtifactView view) {
            Artifact artifact = view.artifact();
            return new ArtifactResponse(
                    artifact.id(),
                    artifact.projectId(),
                    artifact.kind(),
                    artifact.title(),
                    artifact.currentVersionId(),
                    artifact.version(),
                    artifact.createdAt(),
                    artifact.updatedAt(),
                    ArtifactVersionResponse.from(view.currentVersion()));
        }
    }

    /** Immutable version representation with exact typed inputs. */
    public record ArtifactVersionResponse(
            UUID id,
            int versionNo,
            int schemaVersion,
            JsonNode content,
            List<InputReferenceResponse> inputReferences,
            ArtifactVersion.CreatedByKind createdByKind,
            UUID runId,
            Instant createdAt) {

        static ArtifactVersionResponse from(ArtifactVersion version) {
            return new ArtifactVersionResponse(
                    version.id(),
                    version.versionNo(),
                    version.schemaVersion(),
                    version.content(),
                    version.inputReferences().stream()
                            .map(InputReferenceResponse::from)
                            .toList(),
                    version.createdByKind(),
                    version.runId(),
                    version.createdAt());
        }
    }

    /** Public semantic input reference. */
    public record InputReferenceResponse(
            UUID versionId, String role, int order, Artifact.Kind kind) {

        static InputReferenceResponse from(ArtifactVersion.InputReference reference) {
            return new InputReferenceResponse(
                    reference.versionId(),
                    reference.role(),
                    reference.order(),
                    reference.expectedKind());
        }
    }

    /** Immutable version history response. */
    public record ArtifactVersionListResponse(List<ArtifactVersionResponse> items) {}
}
