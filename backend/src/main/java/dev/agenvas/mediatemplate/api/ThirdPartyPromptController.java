package dev.agenvas.mediatemplate.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptImportService;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptService;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptSource;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ThirdPartyPromptController {
    private final ThirdPartyPromptService prompts;
    private final ThirdPartyPromptImportService imports;
    public ThirdPartyPromptController(ThirdPartyPromptService prompts, ThirdPartyPromptImportService imports) { this.prompts = prompts; this.imports = imports; }
    public record Sources(List<ThirdPartyPromptSource> items) {}
    @GetMapping("/media-templates/third-party/sources") public Sources sources() { return new Sources(prompts.sources()); }
    @GetMapping("/media-templates/third-party")
    public ThirdPartyPromptRepository.Page list(@RequestParam TargetKind targetKind, @RequestParam(required = false) String sourceId,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return prompts.list(targetKind, sourceId, query, offset, limit);
    }
    @PostMapping("/settings/media-template-sources") @ResponseStatus(HttpStatus.CREATED)
    public ThirdPartyPromptSource create(@Valid @RequestBody Create request) { return prompts.create(request.id(), request.name(), request.targetKind(), request.url()); }
    @PatchMapping("/settings/media-template-sources/{sourceId}")
    public ThirdPartyPromptSource enabled(@PathVariable String sourceId, @Valid @RequestBody Enabled request) {
        return prompts.enabled(sourceId, request.enabled(), request.expectedVersion());
    }
    @PostMapping("/settings/media-template-sources/{sourceId}/sync")
    public ThirdPartyPromptService.SyncResult sync(@PathVariable String sourceId) { return prompts.sync(sourceId); }
    @PostMapping("/projects/{projectId}/media-templates/third-party/import")
    public ThirdPartyPromptImportService.ImportResponse importPrompt(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @Valid @RequestBody Import request) { return imports.importPrompt(principal.userId(), projectId, request.promptId(), request.expectedVersion(), request.commandKey()); }
    public record Create(@NotBlank @Size(max = 80) String id, @NotBlank @Size(max = 160) String name,
            @NotNull TargetKind targetKind, @NotBlank @Size(max = 4096) String url) {}
    public record Enabled(@NotNull Boolean enabled, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record Import(@NotBlank @Size(max = 512) String promptId, @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 200) String commandKey) {}
}
