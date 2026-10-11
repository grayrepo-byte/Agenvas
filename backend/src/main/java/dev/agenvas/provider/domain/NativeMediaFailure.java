package dev.agenvas.provider.domain;

/** Permanent native protocol failures stop archival retries without exposing raw upstream responses. */
public final class NativeMediaFailure extends RuntimeException {
    private final String code;
    public NativeMediaFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
