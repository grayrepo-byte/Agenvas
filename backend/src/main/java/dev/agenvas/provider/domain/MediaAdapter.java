package dev.agenvas.provider.domain;

/** A compiled provider protocol. Implementations may not write business tables. */
public interface MediaAdapter {
    String adapterId();
    boolean supports(PortInput input);
    /** Validate pinned credentials and media before creating a billable submission checkpoint. */
    default String preflightFailure(AttemptContext context) { return null; }
    /** Exact origin fingerprint for a provider that echoes the saved request key. */
    default String candidateOriginSha256(AttemptContext context) { return null; }
    Submission submit(AttemptContext context);
    Submission reconcile(AttemptContext context);
}
