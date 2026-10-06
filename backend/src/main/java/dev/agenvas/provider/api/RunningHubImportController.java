package dev.agenvas.provider.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.provider.application.RunningHubImportService;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.task.domain.Task;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ApiMessages messages;
    public RunningHubImportController(RunningHubImportService imports, ApiMessages messages) {
        this.imports = imports;
        this.messages = messages;
    }
    public record PreviewResponse(RunningHubDefinition definition, java.util.List<String> warnings, java.util.List<String> recommendedFieldKeys, String targetName) {}
    public record ImportRequest(@NotNull RunningHubDefinition.TargetType targetType,
            @NotNull @Pattern(regexp = "[0-9]{1,32}") String targetId, @NotNull Task.Kind kind, JsonNode source) {}
    @PostMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(@AuthenticationPrincipal AdminPrincipal administrator,
            @PathVariable UUID connectionId, @Valid @RequestBody ImportRequest request, HttpServletRequest httpRequest) {
        Objects.requireNonNull(administrator, "Administrator required");
        var preview = imports.preview(connectionId, request.targetType(), request.targetId(), request.kind(), request.source());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PreviewResponse(preview.definition(),
                preview.warnings().stream().map(warning -> messages.text(warning, httpRequest)).toList(), preview.recommendedFieldKeys(), preview.targetName()));
    }
}
