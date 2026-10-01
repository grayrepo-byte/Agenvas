package dev.agenvas.audit.domain;

/** Actual expired execution units cleaned by this request, not individual SQL row counts.
 * A reached limit means more expired history may remain and requires another manual request.
 */
public record CallLogCleanupResult(int cleanedExecutions, boolean batchLimitReached) {}
