package dev.agenvas.subtitle.runtime;

/** Stable classifications only; signed download URLs and local paths must never escape. */
public final class AsrDownloadException extends RuntimeException {
    public enum Code {
        DOWNLOAD_FAILED, DOWNLOAD_TIMEOUT, INVALID_DOWNLOAD, CHECKSUM_MISMATCH, DISK_LIMIT, VOLUME_UNAVAILABLE, VOLUME_BUSY, INTERRUPTED
    }
    private final Code code;
    public AsrDownloadException(Code code) {
        super(code.name());
        this.code = code;
    }
    public Code code() {
        return code;
    }
}
