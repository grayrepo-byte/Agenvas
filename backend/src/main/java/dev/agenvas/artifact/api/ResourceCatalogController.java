package dev.agenvas.artifact.api;

import dev.agenvas.artifact.application.ResourceCatalogService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/** Authenticated exact-result catalogs, independently of canvas presence and resource defaults. */
@RestController
@Validated
@RequestMapping("/api/v1")
public class ResourceCatalogController {
    private final ResourceCatalogService resources;

    public ResourceCatalogController(ResourceCatalogService resources) {
        this.resources = resources;
    }

    @GetMapping("/resources")
    public ResourceCatalogService.ResourcePage list(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam(required = false) Artifact.Kind kind,
            @RequestParam(defaultValue = "") @Size(max = ResourceCatalogService.MAX_QUERY_LENGTH) String query,
            @RequestParam(required = false) @Size(max = ResourceCatalogService.MAX_CURSOR_LENGTH) String cursor,
            @RequestParam(required = false) @Min(1) @Max(ResourceCatalogService.MAX_PAGE_SIZE) Integer limit) {
        return resources.list(principal.userId(), kind, query, cursor, limit);
    }

    @GetMapping("/projects/{projectId}/resources")
    public ResourceCatalogService.ResourcePage listProject(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestParam(required = false) Artifact.Kind kind,
            @RequestParam(defaultValue = "") @Size(max = ResourceCatalogService.MAX_QUERY_LENGTH) String query,
            @RequestParam(required = false) @Size(max = ResourceCatalogService.MAX_CURSOR_LENGTH) String cursor,
            @RequestParam(required = false) @Min(1) @Max(ResourceCatalogService.MAX_PAGE_SIZE) Integer limit) {
        return resources.listProject(principal.userId(), projectId, kind, query, cursor, limit);
    }
}
