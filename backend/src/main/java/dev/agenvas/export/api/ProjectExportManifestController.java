package dev.agenvas.export.api;

import dev.agenvas.export.application.ProjectExportManifestService;
import dev.agenvas.identity.application.AdminPrincipal;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated JSON download; media bytes remain behind their own protected endpoints. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/export-manifest")
public class ProjectExportManifestController {

    private final ProjectExportManifestService manifests;

    public ProjectExportManifestController(ProjectExportManifestService manifests) {
        this.manifests = manifests;
    }

    /** Serves a redacted MVCC manifest with no signed links or raw configuration. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProjectExportManifestService.Manifest> get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"agenvas-project-" + projectId + ".json\"")
                .body(manifests.build(principal.userId(), projectId));
    }
}
