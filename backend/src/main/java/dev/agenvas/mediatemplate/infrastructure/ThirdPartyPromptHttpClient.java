package dev.agenvas.mediatemplate.infrastructure;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation;
import dev.agenvas.shared.http.PublicDownloadHttpClients;
import dev.agenvas.shared.http.PublicDownloadException;
import java.io.IOException;
import java.time.Duration;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.stereotype.Component;

/** Bounded credential-free GETs; HTTP(S), no redirects or implicit retries. */
@Component
public class ThirdPartyPromptHttpClient {
    public static final int MAX_FEED_BYTES = 32 * 1024 * 1024;
    public static final int MAX_MEDIA_BYTES = 100 * 1024 * 1024;
    public enum FailureReason {
        HTTP_ERROR, TOO_LARGE, DOWNLOAD_FAILED, INVALID_REQUEST, DNS_FAILED, PROXY_UNREACHABLE, PROXY_AUTH_REQUIRED, PROXY_ERROR, TLS_FAILED, REMOTE_RATE_LIMITED
    }
    /** Safe categories only; upstream bodies, addresses and exception prose are never public status. */
    public static final class DownloadFailure extends RuntimeException {
        private final FailureReason reason;
        public DownloadFailure(FailureReason reason, Throwable cause) {
            super("THIRD_PARTY_SOURCE_" + reason.name(), cause);
            this.reason = reason;
        }
        public FailureReason reason() {
            return reason;
        }
    }
    private final OkHttpClient client;
    public ThirdPartyPromptHttpClient() {
        this(new PublicDownloadHttpClients());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ThirdPartyPromptHttpClient(PublicDownloadHttpClients clients) {
        this(clients.create(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60)));
    }
    ThirdPartyPromptHttpClient(OkHttpClient client) {
        this.client = client;
    }
    public String feed(String url) {
        return new String(download(url, MAX_FEED_BYTES), java.nio.charset.StandardCharsets.UTF_8);
    }
    public byte[] download(String url, int limit) {
        ThirdPartyPromptValidation.url(url);
        try (var response = client
                .newCall(new Request.Builder().url(url).header("User-Agent", "Agenvas-Prompt-Sync/1").get().build())
                .execute()) {
            if (response.code() == 429
                    || response.code() == 403 && "0".equals(response.header("X-RateLimit-Remaining")))
                throw new DownloadFailure(FailureReason.REMOTE_RATE_LIMITED, null);
            if (!response.isSuccessful() || response.body() == null)
                throw new DownloadFailure(FailureReason.HTTP_ERROR, null);
            if (response.body().contentLength() > limit)
                throw new DownloadFailure(FailureReason.TOO_LARGE, null);
            try (var input = response.body().byteStream()) {
                byte[] data = input.readNBytes(limit + 1);
                if (data.length > limit)
                    throw new DownloadFailure(FailureReason.TOO_LARGE, null);
                return data;
            }
        } catch (IOException failure) {
            var reason = PublicDownloadException.reason(failure);
            throw new DownloadFailure(reason == PublicDownloadException.Reason.REMOTE_UNAVAILABLE
                    ? FailureReason.DOWNLOAD_FAILED
                    : FailureReason.valueOf(reason.name()), null);
        }
    }
}
