package dev.agenvas.shared.http;

import java.time.Duration;
import okhttp3.OkHttpClient;

/** Immutable finite limits for one HTTP call; phase limits never extend the whole-call deadline. */
public record HttpTimeouts(int connectSeconds, int writeSeconds, int readSeconds, int callSeconds) {
    public static final int MAX_CONNECT_SECONDS = 120;
    public static final int MAX_CALL_SECONDS = 3_600;

    public HttpTimeouts {
        if (connectSeconds < 1 || connectSeconds > MAX_CONNECT_SECONDS
                || writeSeconds < 1 || writeSeconds > MAX_CALL_SECONDS
                || readSeconds < 1 || readSeconds > MAX_CALL_SECONDS
                || callSeconds < 1 || callSeconds > MAX_CALL_SECONDS
                || connectSeconds > callSeconds || writeSeconds > callSeconds || readSeconds > callSeconds) {
            throw new IllegalArgumentException("Invalid finite HTTP timeouts");
        }
    }

    /** Clone rather than mutate a singleton transport so concurrent capabilities remain independent. */
    public OkHttpClient configure(OkHttpClient base) {
        return base.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectSeconds))
                .writeTimeout(Duration.ofSeconds(writeSeconds))
                .readTimeout(Duration.ofSeconds(readSeconds))
                .callTimeout(Duration.ofSeconds(callSeconds))
                .build();
    }
}
