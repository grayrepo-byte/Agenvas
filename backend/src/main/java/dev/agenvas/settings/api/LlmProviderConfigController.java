package dev.agenvas.settings.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.settings.application.LlmProviderConfigService;
import dev.agenvas.settings.application.LlmDiagnosticService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated administrator-only surface for encrypted LLM settings. */
@RestController
@RequestMapping("/api/v1/settings/llm")
public class LlmProviderConfigController {

    private final LlmProviderConfigService configs;
    private final LlmDiagnosticService diagnostics;

    public LlmProviderConfigController(LlmProviderConfigService configs,
            LlmDiagnosticService diagnostics) {
        this.configs = configs;
        this.diagnostics = diagnostics;
    }

    /** Returns only public configuration metadata; neither credential nor nonce is serialized. */
    @GetMapping
    public ResponseEntity<LlmSettingsResponse> get(
            @AuthenticationPrincipal AdminPrincipal administrator) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(LlmSettingsResponse.from(configs.status()));
    }

    /** Replaces the active version after CSRF, input and expected-version checks. */
    @PutMapping
    public ResponseEntity<LlmSettingsResponse> replace(
            @AuthenticationPrincipal AdminPrincipal administrator,
            @Valid @RequestBody ReplaceLlmSettingsRequest request) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(LlmSettingsResponse.from(configs.replace(request.expectedVersion(),
                        request.endpoint(), request.modelId(), request.apiKey())));
    }

    /** Explicit, potentially billable two-round test; it never invokes application tools. */
    @PostMapping("/diagnose")
    public ResponseEntity<LlmSettingsResponse> diagnose(
            @AuthenticationPrincipal AdminPrincipal administrator,
            @Valid @RequestBody DiagnoseLlmRequest request) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(LlmSettingsResponse.from(diagnostics.diagnose(
                        request.expectedVersion(), request.acknowledgeCost())));
    }

    /** The UI must acknowledge the Provider may charge for both diagnostic calls. */
    public record DiagnoseLlmRequest(@Min(1) int expectedVersion,
            boolean acknowledgeCost) {}

    /** Every write includes the full new credential; no blank-key reuse is implicit. */
    public record ReplaceLlmSettingsRequest(@Min(0) int expectedVersion,
            @NotBlank @Size(max = 500) String endpoint,
            @NotBlank @Size(max = 160) String modelId,
            @NotBlank @Size(min = 8, max = 4096) String apiKey) {}

    /** Safe status shape for UI display and optimistic edits. */
    public record LlmSettingsResponse(boolean configured, int version, String endpoint,
            String modelId, String keyMask, boolean toolCallingVerified, Instant updatedAt) {
        static LlmSettingsResponse from(LlmProviderConfigService.Status value) {
            return new LlmSettingsResponse(value.configured(), value.version(),
                    value.endpoint(), value.modelId(), value.keyMask(),
                    value.toolCallingVerified(), value.updatedAt());
        }
    }
}
