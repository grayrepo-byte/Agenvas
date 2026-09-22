package dev.agenvas.identity.api;

import dev.agenvas.identity.application.SetupStatusService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class SetupStatusController {

    private final SetupStatusService setupStatusService;

    public SetupStatusController(SetupStatusService setupStatusService) {
        this.setupStatusService = setupStatusService;
    }

    @GetMapping("/setup-status")
    public ResponseEntity<SetupStatusResponse> getSetupStatus() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SetupStatusResponse(setupStatusService.isSetupRequired()));
    }

    public record SetupStatusResponse(boolean setupRequired) {}
}
