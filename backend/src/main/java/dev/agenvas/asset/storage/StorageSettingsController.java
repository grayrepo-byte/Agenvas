package dev.agenvas.asset.storage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Administrator-only, CSRF-protected settings. Responses contain no usable credentials. */
@RestController
@RequestMapping("/api/v1/settings/storage")
public class StorageSettingsController {
    private final StorageSettingsService settings;
    public StorageSettingsController(StorageSettingsService settings) { this.settings = settings; }
    @GetMapping public ResponseEntity<StorageSettingsService.Status> get() { return response(settings.status()); }
    @PostMapping("/profiles") public ResponseEntity<StorageSettingsService.Status> create(@Valid @RequestBody Create request) {
        return response(settings.create(request.expectedVersion(), request.name(), request.provider(), request.endpoint(),
                request.region(), request.bucket(), request.keyPrefix(), request.pathStyle(), request.accessKeyId(), request.secretAccessKey()));
    }
    @PutMapping("/active") public ResponseEntity<StorageSettingsService.Status> activate(@Valid @RequestBody Activate request) {
        return response(settings.activate(request.expectedVersion(), request.profileId()));
    }
    @PutMapping("/relay") public ResponseEntity<StorageSettingsService.Status> relay(@Valid @RequestBody Activate request) {
        return response(settings.activateRelay(request.expectedVersion(), request.profileId()));
    }
    @PutMapping("/profiles/{id}/credentials") public ResponseEntity<StorageSettingsService.Status> rotate(
            @PathVariable UUID id, @Valid @RequestBody Rotate request) {
        return response(settings.rotate(request.expectedVersion(), id, request.accessKeyId(), request.secretAccessKey()));
    }
    private ResponseEntity<StorageSettingsService.Status> response(StorageSettingsService.Status result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    public record Create(@NotNull @Min(0) Integer expectedVersion, @NotBlank String name, @NotNull StorageProfile.Provider provider,
            @NotBlank String endpoint, @NotBlank String region, @NotBlank String bucket, String keyPrefix,
            boolean pathStyle, @NotBlank String accessKeyId, @NotBlank String secretAccessKey) {}
    public record Activate(@NotNull @Min(0) Integer expectedVersion, UUID profileId) {}
    public record Rotate(@NotNull @Min(0) Integer expectedVersion, @NotBlank String accessKeyId, @NotBlank String secretAccessKey) {}
}
