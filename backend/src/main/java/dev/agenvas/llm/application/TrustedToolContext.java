package dev.agenvas.llm.application;

import java.util.UUID;

/** Server-owned identity and scope; these fields are never model tool parameters. */
public record TrustedToolContext(UUID ownerId, UUID projectId, UUID runId) {

    /** Rejects missing authentication or scope before any ledger access. */
    public TrustedToolContext {
        if (ownerId == null || projectId == null || runId == null) {
            throw new IllegalArgumentException("Trusted tool context requires owner, project and Run");
        }
    }
}
