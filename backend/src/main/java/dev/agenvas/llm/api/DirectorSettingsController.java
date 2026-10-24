package dev.agenvas.llm.api;

import dev.agenvas.llm.application.DirectorSettings;
import dev.agenvas.llm.application.DirectorSettingsService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only settings. No model call or active checkpoint mutation occurs here. */
@RestController
@RequestMapping("/api/v1/settings/director")
public class DirectorSettingsController {
    private final DirectorSettingsService settings;
    public DirectorSettingsController(DirectorSettingsService settings) { this.settings = settings; }
    @GetMapping public ResponseEntity<DirectorSettings> get() { return response(settings.get()); }
    @PutMapping public ResponseEntity<DirectorSettings> update(@Valid @RequestBody Request request) {
        DirectorSettings value;
        try {
            value = new DirectorSettings(request.contextWindowTokens(), request.compactionThresholdPercent(),
                    request.responseReserveTokens(), request.summaryTokens(), request.recentToolRounds(),
                    request.recoveryWindowMinutes(), request.maxModelRetries(), request.retryMaxDelaySeconds(),
                    request.maxRepairAttempts(), request.expectedVersion());
        } catch (IllegalArgumentException invalid) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.director-settings.title"), ApiMessage.of("api.director-settings.invalid"), false);
        }
        return response(settings.update(value));
    }
    private ResponseEntity<DirectorSettings> response(DirectorSettings value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }
    public record Request(@NotNull Integer contextWindowTokens, @NotNull Integer compactionThresholdPercent,
            @NotNull Integer responseReserveTokens, @NotNull Integer summaryTokens, @NotNull Integer recentToolRounds,
            @NotNull Integer recoveryWindowMinutes, @NotNull Integer maxModelRetries, @NotNull Integer retryMaxDelaySeconds,
            @NotNull Integer maxRepairAttempts, @NotNull Long expectedVersion) {}
}
