package dev.agenvas.shared.error;

import org.springframework.http.HttpStatus;

/** Exception carrying a stable public API problem code without exposing internal details. */
public final class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final boolean retryable;

    public ApiProblemException(
            HttpStatus status, String code, String title, String detail, boolean retryable) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public boolean retryable() {
        return retryable;
    }
}
