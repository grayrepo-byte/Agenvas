package dev.agenvas.mediatemplate.infrastructure;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.stereotype.Component;

/** Bounded credential-free GETs; public HTTP(S), checked DNS, no redirects or implicit retries. */
@Component
public class ThirdPartyPromptHttpClient {
    public static final int MAX_FEED_BYTES = 32 * 1024 * 1024;
    public static final int MAX_MEDIA_BYTES = 100 * 1024 * 1024;
    public enum FailureReason { HTTP_ERROR, TOO_LARGE, DOWNLOAD_FAILED }
    /** Safe categories only; upstream bodies, addresses and exception prose are never public status. */
    public static final class DownloadFailure extends RuntimeException {
        private final FailureReason reason;
        public DownloadFailure(FailureReason reason, Throwable cause) {
            super("THIRD_PARTY_SOURCE_" + reason.name(), cause);
            this.reason = reason;
        }
        public FailureReason reason() { return reason; }
    }
    private final OkHttpClient client;
    public ThirdPartyPromptHttpClient() { this(defaultClient()); }
    ThirdPartyPromptHttpClient(OkHttpClient client) { this.client = client; }
    private static OkHttpClient defaultClient() { return PinnedHttpClients.pinned(host -> {
        var addresses = Dns.SYSTEM.lookup(host);
        if (addresses.isEmpty() || addresses.stream().anyMatch(a -> !publicAddress(a))) throw new java.net.UnknownHostException("Blocked prompt source address");
        return addresses;
    }, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60)); }
    public String feed(String url) { return new String(download(url, MAX_FEED_BYTES), java.nio.charset.StandardCharsets.UTF_8); }
    public byte[] download(String url, int limit) {
        var uri = ThirdPartyPromptValidation.url(url);
        // OkHttp bypasses custom DNS for literal IPs, so disallow them before dispatch.
        if (uri.getHost().contains(":") || uri.getHost().matches("[0-9.]+")) throw ThirdPartyPromptValidation.invalid();
        try (var response = client.newCall(new Request.Builder().url(url).header("User-Agent", "Agenvas-Prompt-Sync/1").get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new DownloadFailure(FailureReason.HTTP_ERROR, null);
            if (response.body().contentLength() > limit) throw new DownloadFailure(FailureReason.TOO_LARGE, null);
            try (var input = response.body().byteStream()) {
                byte[] data = input.readNBytes(limit + 1);
                if (data.length > limit) throw new DownloadFailure(FailureReason.TOO_LARGE, null);
                return data;
            }
        } catch (IOException failure) { throw new DownloadFailure(FailureReason.DOWNLOAD_FAILED, failure); }
    }
    static boolean publicAddress(InetAddress address) {
        return dev.agenvas.shared.security.EndpointAddressRules.allowsPublicDownload(address);
    }
}
