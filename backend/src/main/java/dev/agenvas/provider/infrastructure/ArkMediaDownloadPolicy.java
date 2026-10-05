package dev.agenvas.provider.infrastructure;

import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Fetches only fixed Ark result hosts through a DNS-validated, redirect-free connection. */
@Component
public class ArkMediaDownloadPolicy {
    private static final URI OFFICIAL = URI.create(
            "https://ark-acg-cn-beijing.tos-cn-beijing.volces.com");
    private final URI origin;
    private final OkHttpClient http;

    @Autowired
    public ArkMediaDownloadPolicy() {
        this(OFFICIAL, Dns.SYSTEM, false);
    }

    /** Test-only loopback transport; no runtime configuration or admin API exposes it. */
    public static ArkMediaDownloadPolicy forLoopbackTest(URI origin) {
        return new ArkMediaDownloadPolicy(origin, Dns.SYSTEM, true);
    }

    ArkMediaDownloadPolicy(URI origin, Dns resolver, boolean loopbackTest) {
        if (!OFFICIAL.equals(origin) && !(loopbackTest
                && "http".equals(origin.getScheme())
                && "127.0.0.1".equals(origin.getHost()) && origin.getPort() > 0
                && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                && origin.getRawUserInfo() == null && origin.getRawQuery() == null
                && origin.getRawFragment() == null)) {
            throw new IllegalArgumentException("Ark result origin is not fixed");
        }
        this.origin = origin;
        this.http = PinnedHttpClients.pinned(FixedCloudDns.checked(resolver, loopbackTest),
                Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofMinutes(3));
    }

    /** Rejects a URL before the HTTP client can resolve or follow anything from it. */
    public void validate(URI url) {
        if (url == null || !origin.getScheme().equalsIgnoreCase(url.getScheme())
                || !origin.getHost().equalsIgnoreCase(url.getHost())
                || effectivePort(origin) != effectivePort(url)
                || url.getRawUserInfo() != null || url.getRawFragment() != null
                || url.getRawPath() == null || url.getRawPath().isEmpty()
                || url.getRawPath().contains("..")) {
            throw new Rejected("ARK_MEDIA_URL_REJECTED");
        }
    }

    /** Caller owns sink; bytes are bounded before any media parser sees the result. */
    public long download(URI url, OutputStream sink, long maxBytes) {
        validate(url);
        if (maxBytes < 12 || maxBytes > 500L * 1024 * 1024) {
            throw new IllegalArgumentException("Ark download byte bound is invalid");
        }
        Request request = new Request.Builder().url(url.toString()).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 403 || response.code() == 404) {
                throw new Expired();
            }
            if (response.isRedirect()) throw new Rejected("ARK_MEDIA_REDIRECT_REJECTED");
            if (response.code() != 200 || response.body() == null) {
                throw new TechnicalFailure("Ark media response unavailable");
            }
            String type = response.header("Content-Type", "").split(";", 2)[0].trim()
                    .toLowerCase(java.util.Locale.ROOT);
            if (!"video/mp4".equals(type) && !"application/octet-stream".equals(type)) {
                throw new Rejected("ARK_MEDIA_MIME_REJECTED");
            }
            long declared = response.body().contentLength();
            if (declared > maxBytes) throw new Rejected("ARK_MEDIA_TOO_LARGE");
            InputStream input = response.body().byteStream();
            byte[] first = input.readNBytes(12);
            if (first.length < 12 || first[4] != 'f' || first[5] != 't'
                    || first[6] != 'y' || first[7] != 'p') {
                throw new Rejected("ARK_MEDIA_INVALID_MP4");
            }
            sink.write(first);
            long total = first.length;
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new Rejected("ARK_MEDIA_TOO_LARGE");
                sink.write(buffer, 0, count);
            }
            return total;
        } catch (IOException failure) {
            throw new TechnicalFailure("Ark media stream failed");
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80)
                : uri.getPort();
    }

    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code); }
    }
    public static final class Expired extends RuntimeException {
        public Expired() { super("ARK_MEDIA_URL_EXPIRED"); }
    }
    public static final class TechnicalFailure extends RuntimeException {
        public TechnicalFailure(String message) { super(message); }
    }
}
