package dev.agenvas.mediatemplate.api;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptSyncSettings;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptSyncSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** The settings namespace enforces administrator access; writes also require CSRF. */
@RestController
@RequestMapping("/api/v1/settings/media-template-sync")
public class ThirdPartyPromptSyncSettingsController {
    private final ThirdPartyPromptSyncSettingsService settings;
    public ThirdPartyPromptSyncSettingsController(ThirdPartyPromptSyncSettingsService settings) { this.settings = settings; }

    @GetMapping public ResponseEntity<ThirdPartyPromptSyncSettings> get() { return response(settings.get()); }
    @PutMapping public ResponseEntity<ThirdPartyPromptSyncSettings> update(@Valid @RequestBody Request request) {
        return response(settings.update(request.enabled(), request.expectedVersion()));
    }
    private ResponseEntity<ThirdPartyPromptSyncSettings> response(ThirdPartyPromptSyncSettings value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }
    public record Request(@NotNull Boolean enabled, @NotNull @PositiveOrZero Long expectedVersion) {}
}
