package dev.agenvas.mcp.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.application.McpTokenService.Created;
import dev.agenvas.mcp.application.McpTokenService.Summary;
import dev.agenvas.mcp.domain.McpScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account credential management remains protected by the normal browser Session and CSRF chain. */
@RestController
@RequestMapping("/api/v1/account/mcp-tokens")
public class McpTokenController {
    private final McpTokenService tokens;

    public McpTokenController(McpTokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    public ResponseEntity<List<Summary>> list(@AuthenticationPrincipal AdminPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tokens.list(userId(principal)));
    }

    @PostMapping
    public ResponseEntity<Created> create(@AuthenticationPrincipal AdminPrincipal principal,
            @Valid @RequestBody CreateRequest request) {
        Created created = tokens.create(userId(principal),
                new McpTokenService.Create(request.name(), request.scopes(), request.expiresInDays()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(created);
    }

    @PostMapping("/{tokenId}/revoke")
    public ResponseEntity<Summary> revoke(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID tokenId, @Valid @RequestBody RevokeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tokens.revoke(userId(principal), tokenId, request.expectedVersion()));
    }

    private UUID userId(AdminPrincipal principal) {
        if (principal == null) throw McpTokenService.unauthenticated();
        return principal.userId();
    }

    public record CreateRequest(@NotBlank @Size(max = 100) String name,
            @NotEmpty @Size(max = 3) Set<@NotNull McpScope> scopes,
            @Min(1) @Max(90) Integer expiresInDays) {}

    public record RevokeRequest(@NotNull @Min(0) Long expectedVersion) {}
}
