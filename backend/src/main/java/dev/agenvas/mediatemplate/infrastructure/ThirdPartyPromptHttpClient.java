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

/** Bounded credential-free GETs; public HTTPS, checked DNS, no redirects or implicit retries. */
@Component
public class ThirdPartyPromptHttpClient {
    public static final int MAX_FEED_BYTES = 32 * 1024 * 1024;
    public static final int MAX_MEDIA_BYTES = 100 * 1024 * 1024;
    private final OkHttpClient client = PinnedHttpClients.pinned(host -> {
        var addresses = Dns.SYSTEM.lookup(host);
        if (addresses.isEmpty() || addresses.stream().anyMatch(a -> !publicAddress(a))) throw new java.net.UnknownHostException("Blocked prompt source address");
        return addresses;
    }, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60));
    public String feed(String url) { return new String(download(url, MAX_FEED_BYTES), java.nio.charset.StandardCharsets.UTF_8); }
    public byte[] download(String url, int limit) {
        var uri = ThirdPartyPromptValidation.url(url);
        // OkHttp bypasses custom DNS for literal IPs, so disallow them before dispatch.
        if (uri.getHost().contains(":") || uri.getHost().matches("[0-9.]+")) throw ThirdPartyPromptValidation.invalid();
        try (var response = client.newCall(new Request.Builder().url(url).header("User-Agent", "Agenvas-Prompt-Sync/1").get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Prompt download rejected");
            if (response.body().contentLength() > limit) throw new IOException("Prompt download exceeds limit");
            try (var input = response.body().byteStream()) {
                byte[] data = input.readNBytes(limit + 1);
                if (data.length > limit) throw new IOException("Prompt download exceeds limit");
                return data;
            }
        } catch (IOException failure) { throw new IllegalStateException("THIRD_PARTY_DOWNLOAD_FAILED", failure); }
    }
    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress(); int first = b[0] & 255;
        if (b.length == 4) {
            int second = b[1] & 255;
            return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 198 && (second == 18 || second == 19));
        }
        return (first & 0xfe) != 0xfc && first != 0;
    }
}
