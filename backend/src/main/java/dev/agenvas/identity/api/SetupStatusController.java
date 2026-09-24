package dev.agenvas.identity.api;

import dev.agenvas.identity.application.SetupStatusService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only installation state endpoint used before administrator setup. */
@RestController
@RequestMapping("/api/v1/auth")
public class SetupStatusController {

    private final SetupStatusService setupStatusService;

    public SetupStatusController(SetupStatusService setupStatusService) {
        this.setupStatusService = setupStatusService;
    }

    /** Returns a non-cacheable setup flag without exposing account data. */
    @GetMapping("/setup-status")
    public ResponseEntity<SetupStatusResponse> getSetupStatus() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SetupStatusResponse(setupStatusService.isSetupRequired()));
    }

    /** Minimal public setup state. */
    public record SetupStatusResponse(boolean setupRequired) {}
}
