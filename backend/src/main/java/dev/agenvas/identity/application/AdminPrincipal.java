package dev.agenvas.identity.application;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/** Minimal administrator identity stored in the database-backed HTTP session. */
public record AdminPrincipal(UUID userId, String loginName) implements Principal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Supplies Spring Security and Spring Session with the stable principal index. */
    @Override
    public String getName() {
        return loginName;
    }
}
