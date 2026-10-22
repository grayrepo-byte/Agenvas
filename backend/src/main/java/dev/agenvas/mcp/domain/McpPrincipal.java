package dev.agenvas.mcp.domain;

import java.security.Principal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Per-request identity from an authenticated credential; transport sessions convey no authority. */
public record McpPrincipal(UUID userId, UUID tokenId, Set<McpScope> scopes) implements Principal {
    public McpPrincipal {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(tokenId);
        scopes = Collections.unmodifiableSet(scopes.isEmpty()
                ? EnumSet.noneOf(McpScope.class) : EnumSet.copyOf(scopes));
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
