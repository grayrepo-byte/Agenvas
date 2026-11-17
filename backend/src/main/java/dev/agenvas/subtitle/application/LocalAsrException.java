package dev.agenvas.subtitle.application;

/** Safe public failure classification; native output, model paths and source filenames are excluded. */
public final class LocalAsrException extends IllegalStateException {
    public enum Code {
        NOT_CONFIGURED, UNAVAILABLE, BUSY, INVALID_SOURCE, NO_AUDIO, TIMED_OUT, FAILED, INVALID_OUTPUT, SCRATCH_LIMIT
    }
    private final Code code;

    public LocalAsrException(Code code) {
        super("LOCAL_ASR_" + code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
