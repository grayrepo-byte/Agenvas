package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Exact documented Wan result buckets, bounded GETs, and no API credentials or redirects. */
@Component
public class WanMediaDownloadPolicy {
    private static final Set<String> HOSTS = Set.of("dashscope-result-bj.oss-cn-beijing.aliyuncs.com",
            "dashscope-result-sh.oss-accelerate.aliyuncs.com");
    private final URI testOrigin;
    private final OkHttpClient http;
    @Autowired public WanMediaDownloadPolicy() { this(null, Dns.SYSTEM); }
    private WanMediaDownloadPolicy(URI testOrigin, Dns dns) {
        WanClient.requireLoopback(testOrigin); this.testOrigin = testOrigin;
        this.http = PinnedHttpClients.pinned(FixedCloudDns.checked(dns, testOrigin != null),
                Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofMinutes(3));
    }
    public static WanMediaDownloadPolicy forLoopbackTest(URI origin) { return new WanMediaDownloadPolicy(origin, Dns.SYSTEM); }
    public void validate(URI url) {
        String host = url == null || url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        boolean official = url != null && "https".equals(url.getScheme()) && (url.getPort() == -1 || url.getPort() == 443) && HOSTS.contains(host);
        boolean testing = url != null && testOrigin != null && testOrigin.getScheme().equals(url.getScheme())
                && testOrigin.getHost().equals(host) && testOrigin.getPort() == url.getPort();
        String path = url == null ? null : url.getRawPath();
        if ((!official && !testing) || url.getRawUserInfo() != null || url.getRawFragment() != null
                || path == null || path.isEmpty() || path.contains("..") || path.contains("\\") || path.contains("//")
                || path.contains("%")) throw new NativeMediaFailure("PROVIDER_RESULT_URL_INVALID");
    }
    public String download(URI url, OutputStream sink, boolean video) {
        validate(url);
        long maximum = (video ? 500L : 100L) * 1024 * 1024;
        try (Response response = http.newCall(new Request.Builder().url(url.toString()).get().build()).execute()) {
            if (Set.of(403, 404, 410).contains(response.code())) throw new Expired();
            if (response.isRedirect()) throw new NativeMediaFailure("PROVIDER_RESULT_URL_INVALID");
            if (response.code() != 200 || response.body() == null) throw new TechnicalFailure();
            if (response.body().contentLength() > maximum) throw new NativeMediaFailure("PROVIDER_RESULT_TOO_LARGE");
            String type = response.header("Content-Type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!(video ? Set.of("video/mp4", "application/octet-stream") : Set.of("image/png", "application/octet-stream")).contains(type))
                throw WanClient.protocolFailure();
            InputStream source = response.body().byteStream();
            byte[] first = source.readNBytes(12);
            if (first.length < 12 || (video ? !(first[4] == 'f' && first[5] == 't' && first[6] == 'y' && first[7] == 'p')
                    : !(first[0] == (byte) 0x89 && first[1] == 'P' && first[2] == 'N' && first[3] == 'G'
                        && first[4] == 13 && first[5] == 10 && first[6] == 26 && first[7] == 10))) throw WanClient.protocolFailure();
            sink.write(first);
            long total = first.length;
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = source.read(buffer)) != -1) {
                total += count;
                if (total > maximum) throw new NativeMediaFailure("PROVIDER_RESULT_TOO_LARGE");
                sink.write(buffer, 0, count);
            }
            return video ? "video/mp4" : "image/png";
        } catch (IOException unavailable) { throw new TechnicalFailure(); }
    }
    public static final class Expired extends RuntimeException { public Expired() { super(WanClient.RESULT_EXPIRED); } }
    public static final class TechnicalFailure extends RuntimeException { public TechnicalFailure() { super("Wan result download unavailable"); } }
}
