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

/** Downloads HTTP(S) result URLs without credentials, redirects or unbounded response bodies. */
@Component
public class KlingMediaDownloadPolicy {
    private final OkHttpClient http;

    @Autowired
    public KlingMediaDownloadPolicy() {
        this.http = PinnedHttpClients.pinned(Dns.SYSTEM,
                Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofMinutes(3));
    }

    public void validate(URI url) {
        try {
            dev.agenvas.shared.security.ServiceEndpointPolicy.downloadDns(url, Dns.SYSTEM);
        } catch (IllegalArgumentException | NullPointerException | java.net.UnknownHostException invalid) {
            throw new Rejected("PROVIDER_RESULT_URL_INVALID");
        }
        String path = url.getRawPath();
        if (path == null || path.isEmpty() || path.contains("..") || path.contains("\\")
                || path.contains("%") || path.contains("//"))
            throw new Rejected("PROVIDER_RESULT_URL_INVALID");
    }

    /** Bytes are bounded and magic-checked here; the application archive performs full decoding. */
    public String download(URI url, OutputStream sink, boolean video, long maximum) {
        validate(url);
        if (maximum < 12 || maximum > 500L * 1024 * 1024)
            throw new IllegalArgumentException("Invalid Kling result byte bound");
        try (Response response = http.newCall(new Request.Builder().url(url.toString()).get().build()).execute()) {
            if (response.code() == 403 || response.code() == 404 || response.code() == 410)
                throw new Expired();
            if (response.isRedirect())
                throw new Rejected("PROVIDER_RESULT_URL_INVALID");
            if (response.code() != 200 || response.body() == null)
                throw new TechnicalFailure();
            if (response.body().contentLength() > maximum)
                throw new Rejected("PROVIDER_RESULT_TOO_LARGE");
            String type = response.header("Content-Type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            Set<String> types = video
                    ? Set.of("video/mp4", "application/octet-stream")
                    : Set.of("image/png", "image/jpeg", "application/octet-stream");
            if (!types.contains(type))
                throw new Rejected("PROVIDER_PROTOCOL_INVALID");
            InputStream input = response.body().byteStream();
            byte[] first = input.readNBytes(12);
            if (first.length < 12)
                throw new Rejected("PROVIDER_PROTOCOL_INVALID");
            String actualType;
            if (video && first[4] == 'f' && first[5] == 't' && first[6] == 'y' && first[7] == 'p')
                actualType = "video/mp4";
            else if (!video && first[0] == (byte) 0x89 && first[1] == 'P' && first[2] == 'N' && first[3] == 'G'
                    && first[4] == 13 && first[5] == 10 && first[6] == 26 && first[7] == 10)
                actualType = "image/png";
            else if (!video && first[0] == (byte) 0xff && first[1] == (byte) 0xd8 && first[2] == (byte) 0xff)
                actualType = "image/jpeg";
            else
                throw new Rejected("PROVIDER_PROTOCOL_INVALID");
            if (!"application/octet-stream".equals(type) && !actualType.equals(type))
                throw new Rejected("PROVIDER_PROTOCOL_INVALID");
            sink.write(first);
            long total = first.length;
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximum)
                    throw new Rejected("PROVIDER_RESULT_TOO_LARGE");
                sink.write(buffer, 0, count);
            }
            return actualType;
        } catch (IOException unavailable) {
            throw new TechnicalFailure();
        }
    }

    public static final class Rejected extends RuntimeException {
        public Rejected(String code) {
            super(code);
        }
    }
    public static final class Expired extends RuntimeException {
        public Expired() {
            super("KLING_RESULT_EXPIRED");
        }
    }
    public static final class TechnicalFailure extends RuntimeException {
        public TechnicalFailure() {
            super("Kling result download unavailable");
        }
    }
}
