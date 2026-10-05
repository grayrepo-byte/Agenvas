package dev.agenvas.audit.api;

import dev.agenvas.audit.application.CallLogRetentionService;
import dev.agenvas.audit.domain.CallLogRetentionSettings;
import dev.agenvas.audit.domain.CallLogCleanupResult;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.validation.Valid;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/call-log-retention")
public class CallLogRetentionController {
    private final CallLogRetentionService retention;
    public CallLogRetentionController(CallLogRetentionService retention) { this.retention = retention; }
    @GetMapping public ResponseEntity<CallLogRetentionSettings> get() { return response(retention.settings()); }
    @PutMapping public ResponseEntity<CallLogRetentionSettings> update(@Valid @RequestBody Request request) {
        return response(retention.update(request.retentionDays(), request.expectedVersion()));
    }
    @PostMapping("/cleanup")
    public ResponseEntity<CallLogCleanupResult> cleanup(@Valid @RequestBody CleanupRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(retention.cleanExpired(request.expectedVersion()));
    }
    public record CleanupRequest(@NotNull @Min(1) Integer expectedVersion) {}
    private ResponseEntity<CallLogRetentionSettings> response(CallLogRetentionSettings value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }
    public record Request(@JsonProperty(value = "retentionDays", required = true) @Min(CallLogRetentionSettings.MIN_DAYS) @Max(CallLogRetentionSettings.MAX_DAYS) Integer retentionDays,
            @NotNull @Min(1) Integer expectedVersion) {}
}
