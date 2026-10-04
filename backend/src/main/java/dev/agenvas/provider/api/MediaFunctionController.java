package dev.agenvas.provider.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.provider.application.MediaFunctionService;
import dev.agenvas.task.domain.VideoOperation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated administrators configure function routing independently of model defaults. */
@RestController
@RequestMapping("/api/v1/settings/media-functions")
public class MediaFunctionController {
    private final MediaFunctionService functions;
    public MediaFunctionController(MediaFunctionService functions) { this.functions = functions; }

    @GetMapping
    public List<FunctionResponse> list(@AuthenticationPrincipal AdminPrincipal admin) {
        Objects.requireNonNull(admin);
        return functions.list().stream().map(setting -> new FunctionResponse(setting.operation(), setting.capabilityId(), setting.version())).toList();
    }

    @PutMapping("/{operation}")
    public List<FunctionResponse> update(@AuthenticationPrincipal AdminPrincipal admin,
            @PathVariable VideoOperation operation, @Valid @RequestBody UpdateRequest request) {
        Objects.requireNonNull(admin);
        functions.update(operation, request.expectedVersion(), request.capabilityId());
        return functions.list().stream().map(setting -> new FunctionResponse(setting.operation(), setting.capabilityId(), setting.version())).toList();
    }

    public record FunctionResponse(VideoOperation operation, UUID capabilityId, long version) {}

    public record UpdateRequest(@PositiveOrZero long expectedVersion, UUID capabilityId) {}
}
