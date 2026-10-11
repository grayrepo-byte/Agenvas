package dev.agenvas.provider.infrastructure;

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

/** Downloads official result URLs without credentials, redirects or unbounded response bodies. */
@Component
public class KlingMediaDownloadPolicy {
    private final URI testOrigin;
    private final OkHttpClient http;

    @Autowired public KlingMediaDownloadPolicy() { this(null, Dns.SYSTEM); }

    private KlingMediaDownloadPolicy(URI testOrigin, Dns dns) {
        if (testOrigin != null && !("http".equals(testOrigin.getScheme())
                && "127.0.0.1".equals(testOrigin.getHost()) && testOrigin.getPort() > 0
                && testOrigin.getPort() <= 65535 && "".equals(testOrigin.getRawPath())
                && testOrigin.getRawUserInfo() == null && testOrigin.getRawQuery() == null
                && testOrigin.getRawFragment() == null))
            throw new IllegalArgumentException("Invalid Kling download test origin");
        this.testOrigin = testOrigin;
        this.http = PinnedHttpClients.pinned(FixedCloudDns.checked(dns, testOrigin != null),
                Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofMinutes(3));
    }

    /** This override is injectable only in fake-provider tests. */
    public static KlingMediaDownloadPolicy forLoopbackTest(URI origin) {
        return new KlingMediaDownloadPolicy(origin, Dns.SYSTEM);
    }

    public void validate(URI url) {
        String host = url == null || url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        boolean official = "https".equalsIgnoreCase(url == null ? null : url.getScheme())
                && (url.getPort() == -1 || url.getPort() == 443)
                && ("h1.inkwai.com".equals(host) || host.endsWith(".klingai.com"));
        boolean testing = testOrigin != null && url != null && testOrigin.getScheme().equals(url.getScheme())
                && testOrigin.getHost().equals(host) && testOrigin.getPort() == url.getPort();
        String path = url == null ? null : url.getRawPath();
        if ((!official && !testing) || url.getRawUserInfo() != null || url.getRawFragment() != null
                || path == null || path.isEmpty() || path.contains("..") || path.contains("\\")
                || path.contains("%") || path.contains("//")) throw new Rejected("PROVIDER_RESULT_URL_INVALID");
    }

    /** Bytes are bounded and magic-checked here; the application archive performs full decoding. */
    public String download(URI url, OutputStream sink, boolean video, long maximum) {
        validate(url);
        if (maximum < 12 || maximum > 500L * 1024 * 1024)
            throw new IllegalArgumentException("Invalid Kling result byte bound");
        try (Response response = http.newCall(new Request.Builder().url(url.toString()).get().build()).execute()) {
            if (response.code() == 403 || response.code() == 404 || response.code() == 410) throw new Expired();
            if (response.isRedirect()) throw new Rejected("PROVIDER_RESULT_URL_INVALID");
            if (response.code() != 200 || response.body() == null) throw new TechnicalFailure();
            if (response.body().contentLength() > maximum) throw new Rejected("PROVIDER_RESULT_TOO_LARGE");
            String type = response.header("Content-Type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            Set<String> types = video ? Set.of("video/mp4", "application/octet-stream")
                    : Set.of("image/png", "image/jpeg", "application/octet-stream");
            if (!types.contains(type)) throw new Rejected("PROVIDER_PROTOCOL_INVALID");
            InputStream input = response.body().byteStream();
            byte[] first = input.readNBytes(12);
            if (first.length < 12) throw new Rejected("PROVIDER_PROTOCOL_INVALID");
            String actualType;
            if (video && first[4] == 'f' && first[5] == 't' && first[6] == 'y' && first[7] == 'p') actualType = "video/mp4";
            else if (!video && first[0] == (byte) 0x89 && first[1] == 'P' && first[2] == 'N' && first[3] == 'G'
                    && first[4] == 13 && first[5] == 10 && first[6] == 26 && first[7] == 10) actualType = "image/png";
            else if (!video && first[0] == (byte) 0xff && first[1] == (byte) 0xd8 && first[2] == (byte) 0xff) actualType = "image/jpeg";
            else throw new Rejected("PROVIDER_PROTOCOL_INVALID");
            if (!"application/octet-stream".equals(type) && !actualType.equals(type)) throw new Rejected("PROVIDER_PROTOCOL_INVALID");
            sink.write(first);
            long total = first.length;
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximum) throw new Rejected("PROVIDER_RESULT_TOO_LARGE");
                sink.write(buffer, 0, count);
            }
            return actualType;
        } catch (IOException unavailable) { throw new TechnicalFailure(); }
    }

    public static final class Rejected extends RuntimeException { public Rejected(String code) { super(code); } }
    public static final class Expired extends RuntimeException { public Expired() { super("KLING_RESULT_EXPIRED"); } }
    public static final class TechnicalFailure extends RuntimeException { public TechnicalFailure() { super("Kling result download unavailable"); } }
}
