package dev.agenvas.settings.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.settings.application.SystemDiagnosticsService;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only read endpoint for non-billable local installation diagnostics. */
@RestController
@RequestMapping("/api/v1/settings/diagnostics")
public class SystemDiagnosticsController {

    private final SystemDiagnosticsService diagnostics;

    public SystemDiagnosticsController(SystemDiagnosticsService diagnostics) {
        this.diagnostics = diagnostics;
    }

    /** Never caches or triggers an external Provider request. */
    @GetMapping
    public ResponseEntity<SystemDiagnosticsService.Snapshot> get(
            @AuthenticationPrincipal AdminPrincipal administrator) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(diagnostics.snapshot());
    }
}
