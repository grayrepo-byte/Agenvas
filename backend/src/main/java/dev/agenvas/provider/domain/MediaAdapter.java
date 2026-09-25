package dev.agenvas.provider.domain;

/** A compiled provider protocol. Implementations may not write business tables. */
public interface MediaAdapter {
    String adapterId();
    boolean supports(PortInput input);
    Submission submit(AttemptContext context);
    Submission reconcile(AttemptContext context);
}
