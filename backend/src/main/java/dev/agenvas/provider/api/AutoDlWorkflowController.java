package dev.agenvas.provider.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.provider.application.AutoDlWorkflowDiscoveryService;
import dev.agenvas.provider.domain.AutoDlWorkflowDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/settings/autodl-workflows")
public class AutoDlWorkflowController {
    private final AutoDlWorkflowDiscoveryService discovery;
    public AutoDlWorkflowController(AutoDlWorkflowDiscoveryService discovery) { this.discovery = discovery; }
    public record Catalog(List<AutoDlWorkflowDiscoveryService.Entry> items) {}
    public record Preview(@NotNull @Pattern(regexp = AutoDlWorkflowDefinition.ID_PATTERN) String workflowId, JsonNode source) {}
    @GetMapping public ResponseEntity<Catalog> list(@AuthenticationPrincipal AdminPrincipal administrator) {
        Objects.requireNonNull(administrator, "Administrator required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Catalog(discovery.list()));
    }
    @PostMapping("/preview") public ResponseEntity<JsonNode> preview(@AuthenticationPrincipal AdminPrincipal administrator,
            @Valid @RequestBody Preview request) {
        Objects.requireNonNull(administrator, "Administrator required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(discovery.preview(request.workflowId(), request.source()));
    }
}
