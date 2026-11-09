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

/** Fetches HTTP(S) MiniMax results through a bounded, credential-free connection. */
@Component
public class MiniMaxMediaDownloadPolicy {
    private final OkHttpClient http;

    @Autowired
    public MiniMaxMediaDownloadPolicy() {
        this.http = PinnedHttpClients.pinned(Dns.SYSTEM,
                Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofMinutes(3));
    }

    public void validate(URI url) {
        try { dev.agenvas.shared.security.ServiceEndpointPolicy.downloadDns(url, Dns.SYSTEM); }
        catch (IllegalArgumentException | NullPointerException | java.net.UnknownHostException invalid) { throw new Rejected("PROVIDER_RESULT_URL_INVALID"); }
        String path = url.getRawPath();
        if (path == null || path.isEmpty() || path.contains("..")) throw new Rejected("PROVIDER_RESULT_URL_INVALID");
    }

    /** Caller owns sink; bytes are bounded before any media parser sees the result. */
    public long download(URI url, OutputStream sink, long maxBytes) {
        validate(url);
        if (maxBytes < 12 || maxBytes > 500L * 1024 * 1024) {
            throw new IllegalArgumentException("MiniMax download byte bound is invalid");
        }
        Request request = new Request.Builder().url(url.toString()).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 403 || response.code() == 404) {
                throw new Expired();
            }
            if (response.isRedirect()) throw new Rejected("PROVIDER_RESULT_URL_INVALID");
            if (response.code() != 200 || response.body() == null) {
                throw new TechnicalFailure("MiniMax media response unavailable");
            }
            String type = response.header("Content-Type", "").split(";", 2)[0].trim()
                    .toLowerCase(java.util.Locale.ROOT);
            if (!"video/mp4".equals(type) && !"application/octet-stream".equals(type)) {
                throw new Rejected("PROVIDER_PROTOCOL_INVALID");
            }
            long declared = response.body().contentLength();
            if (declared > maxBytes) throw new Rejected("PROVIDER_RESULT_TOO_LARGE");
            InputStream input = response.body().byteStream();
            byte[] first = input.readNBytes(12);
            if (first.length < 12 || first[4] != 'f' || first[5] != 't'
                    || first[6] != 'y' || first[7] != 'p') {
                throw new Rejected("PROVIDER_PROTOCOL_INVALID");
            }
            sink.write(first);
            long total = first.length;
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new Rejected("PROVIDER_RESULT_TOO_LARGE");
                sink.write(buffer, 0, count);
            }
            return total;
        } catch (IOException failure) {
            throw new TechnicalFailure("MiniMax media stream failed");
        }
    }

    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code); }
    }
    public static final class Expired extends RuntimeException {
        public Expired() { super("MINIMAX_RESULT_EXPIRED"); }
    }
    public static final class TechnicalFailure extends RuntimeException {
        public TechnicalFailure(String message) { super(message); }
    }
}
