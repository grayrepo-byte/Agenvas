package dev.agenvas.asset.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import okhttp3.Credentials;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/** HTTPS Basic authentication, checked DNS, bounded reads and non-overwriting WebDAV publication. */
@Component
public class WebDavStorageClient implements RemoteStorageClient {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WebDavStorageClient.class);
    private final StorageSettingsService settings;
    private final OkHttpClient client;
    @org.springframework.beans.factory.annotation.Autowired
    public WebDavStorageClient(StorageSettingsService settings) {
        this(settings, new OkHttpClient.Builder().dns(ObjectStorageClient.checkedDns(Dns.SYSTEM))
                .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(60)).callTimeout(Duration.ofMinutes(10)).build());
    }
    WebDavStorageClient(StorageSettingsService settings, OkHttpClient client) { this.settings = settings; this.client = client; }
    static HttpUrl url(StorageProfile profile, String key) {
        RemoteFileSupport.key(key);
        return HttpUrl.get(profile.endpoint()).newBuilder().addPathSegments(key).build();
    }
    private Response execute(StorageProfile p, Request.Builder request) throws IOException {
        String[] credentials = settings.credentials(p);
        return client.newCall(request.header("Authorization", Credentials.basic(credentials[0], credentials[1], StandardCharsets.UTF_8))
                .header("Accept-Encoding", "identity").build()).execute();
    }
    private long size(StorageProfile p, String key) {
        try (var response = execute(p, new Request.Builder().url(url(p, key)).head())) {
            if (response.code() == 404) return -1;
            if (!response.isSuccessful()) throw RemoteFileSupport.unavailable();
            long value = Long.parseLong(response.header("Content-Length", "-1"));
            if (value < 0) throw RemoteFileSupport.unavailable();
            return value;
        } catch (IOException | NumberFormatException failure) { throw RemoteFileSupport.unavailable(); }
    }
    @Override public void verify(StorageProfile p, String key, long size, String hash) {
        if (size(p, key) != size) throw RemoteFileSupport.unavailable();
    }
    @Override public boolean matches(StorageProfile p, String key, long size, String hash) {
        long actual = size(p, key);
        if (actual == -1) return false;
        if (actual != size) throw RemoteFileSupport.conflict();
        try (var input = open(p, key, 0, size, size)) {
            if (!RemoteFileSupport.digestMatches(input, size, hash)) throw RemoteFileSupport.conflict();
            return true;
        } catch (IOException failure) { throw RemoteFileSupport.unavailable(); }
    }
    private void collections(StorageProfile p, String key) throws IOException {
        String[] segments = key.split("/");
        String directory = "";
        // The configured base collection must exist; create only application-owned descendants.
        for (int i = 0; i < segments.length - 1; i++) {
            directory += (directory.isEmpty() ? "" : "/") + segments[i];
            HttpUrl target = url(p, directory).newBuilder().addPathSegment("").build();
            try (var response = execute(p, new Request.Builder().url(target).method("MKCOL", null))) {
                if (response.code() != 201 && response.code() != 405) throw RemoteFileSupport.unavailable();
            }
        }
    }
    @Override public void put(StorageProfile p, String key, Path file, String mime, long size, String hash) {
        if (matches(p, key, size, hash)) return;
        String temporary = key + ".upload-" + UUID.randomUUID();
        try {
            collections(p, key);
            try (var response = execute(p, new Request.Builder().url(url(p, temporary))
                    .header("If-None-Match", "*").put(RequestBody.create(file.toFile(), MediaType.get(mime))))) {
                if (!response.isSuccessful()) throw RemoteFileSupport.unavailable();
            }
            if (!matches(p, temporary, size, hash)) throw RemoteFileSupport.unavailable();
            // MOVE publishes validated bytes in one server operation; F protects an existing final file.
            try (var response = execute(p, new Request.Builder().url(url(p, temporary)).method("MOVE", null)
                    .header("Destination", url(p, key).toString()).header("Overwrite", "F"))) {
                if (response.code() != 201 && response.code() != 204 && response.code() != 412)
                    throw RemoteFileSupport.unavailable();
            }
            if (!matches(p, key, size, hash)) throw RemoteFileSupport.unavailable();
        } catch (IOException failure) { throw RemoteFileSupport.unavailable(); }
        finally {
            try { delete(p, temporary); }
            catch (RuntimeException failure) { LOGGER.warn("Temporary WebDAV archive cleanup failed"); }
        }
    }
    @Override public InputStream open(StorageProfile p, String key, long start, long length, long total) {
        if (start < 0 || length <= 0 || start > total - length) throw new IllegalArgumentException("Invalid archive range");
        Response response;
        try {
            response = execute(p, new Request.Builder().url(url(p, key)).get()
                    .header("Range", "bytes=" + start + "-" + (start + length - 1)));
        } catch (IOException failure) { throw RemoteFileSupport.unavailable(); }
        boolean partial = response.code() == 206
                && Long.toString(length).equals(response.header("Content-Length"))
                && ("bytes " + start + "-" + (start + length - 1) + "/" + total).equals(response.header("Content-Range"));
        boolean full = response.code() == 200 && Long.toString(total).equals(response.header("Content-Length"));
        if (response.body() == null || !partial && !full || !"identity".equals(response.header("Content-Encoding", "identity"))) {
            response.close(); throw RemoteFileSupport.unavailable();
        }
        InputStream input = response.body().byteStream();
        try { if (full) input.skipNBytes(start); }
        catch (IOException failure) { response.close(); throw RemoteFileSupport.unavailable(); }
        return new FilterInputStream(input) {
            private long remaining = length;
            @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff; }
            @Override public int read(byte[] b, int offset, int count) throws IOException {
                if (count == 0) return 0;
                if (remaining == 0) return -1;
                int read = in.read(b, offset, (int) Math.min(count, remaining));
                if (read < 0) throw new IOException("Archive stream ended before recorded size");
                remaining -= read; return read;
            }
            @Override public long skip(long count) throws IOException { long skipped = in.skip(Math.min(Math.max(0, count), remaining)); remaining -= skipped; return skipped; }
            @Override public int available() throws IOException { return (int) Math.min(in.available(), Math.min(remaining, Integer.MAX_VALUE)); }
            @Override public void close() { response.close(); }
        };
    }
    @Override public void delete(StorageProfile p, String key) {
        try (var response = execute(p, new Request.Builder().url(url(p, key)).delete())) {
            if (!response.isSuccessful() && response.code() != 404) throw RemoteFileSupport.unavailable();
        } catch (IOException failure) { throw RemoteFileSupport.unavailable(); }
    }
}
