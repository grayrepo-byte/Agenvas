package dev.agenvas.provider.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.provider.application.RunningHubImportService;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.task.domain.Task;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/settings/media-connections/{connectionId}/runninghub")
public final class RunningHubImportController {
    private final RunningHubImportService imports;
    public RunningHubImportController(RunningHubImportService imports) { this.imports = imports; }
    public record ImportRequest(@NotNull RunningHubDefinition.TargetType targetType,
            @NotNull @Pattern(regexp = "[0-9]{1,32}") String targetId, @NotNull Task.Kind kind, JsonNode source) {}
    @PostMapping("/preview")
    public ResponseEntity<RunningHubImportService.Preview> preview(@AuthenticationPrincipal AdminPrincipal administrator,
            @PathVariable UUID connectionId, @Valid @RequestBody ImportRequest request) {
        Objects.requireNonNull(administrator, "Administrator required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(imports.preview(connectionId, request.targetType(), request.targetId(), request.kind(), request.source()));
    }
}
