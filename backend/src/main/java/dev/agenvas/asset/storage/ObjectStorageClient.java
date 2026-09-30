package dev.agenvas.asset.storage;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.security.EndpointAddressRules;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Private object transport: DNS checked at connection time, no redirects or automatic PUT retries. */
@Component
public class ObjectStorageClient {
    private static final int CHECKSUM_BUFFER_BYTES = 8192;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration IO_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(10);
    private final StorageSettingsService settings;
    private final Clock clock;
    private final OkHttpClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public ObjectStorageClient(StorageSettingsService settings, Clock clock) {
        this(settings, clock, new OkHttpClient.Builder().dns(checkedDns(Dns.SYSTEM))
                .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(IO_TIMEOUT).writeTimeout(IO_TIMEOUT).callTimeout(CALL_TIMEOUT).build());
    }
    ObjectStorageClient(StorageSettingsService settings, Clock clock, OkHttpClient client) {
        this.settings = settings; this.clock = clock; this.client = client;
    }
    static Dns checkedDns(Dns resolver) {
        return hostname -> {
            List<InetAddress> addresses = resolver.lookup(hostname);
            if (addresses.isEmpty()) throw new UnknownHostException("Object storage DNS is empty");
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isMulticastAddress()
                        || address.isSiteLocalAddress() && !EndpointAddressRules.allowsSelfHosted(address))
                    throw new UnknownHostException("Object storage address is blocked");
                byte[] bytes = address.getAddress();
                if (bytes.length == 4 && ((bytes[0] & 0xff) == 0 || (bytes[0] & 0xff) >= 224))
                    throw new UnknownHostException("Object storage address is blocked");
            }
            return addresses;
        };
    }
    static HttpUrl url(StorageProfile p, String key) {
        if (!key.matches("[A-Za-z0-9_./-]+") || key.contains("..") || key.startsWith("/"))
            throw new IllegalArgumentException("Invalid object key");
        HttpUrl endpoint = HttpUrl.get(p.endpoint());
        var url = endpoint.newBuilder();
        if (p.pathStyle()) url.addPathSegment(p.bucket());
        else url.host(p.bucket() + "." + endpoint.host());
        return url.addPathSegments(key).build();
    }
    public void put(StorageProfile p, String key, Path file, String mime, long size, String hash) {
        // Retry an uncertain archive by verifying the identical object; never overwrite differing bytes.
        if (matches(p, key, size, hash)) return;
        RequestBody body = RequestBody.create(file.toFile(), MediaType.get(mime));
        String md5;
        try {
            var digest = java.security.MessageDigest.getInstance("MD5");
            try (var input = new java.security.DigestInputStream(java.nio.file.Files.newInputStream(file), digest)) {
                byte[] buffer = new byte[CHECKSUM_BUFFER_BYTES]; while (input.read(buffer) != -1) { /* streaming integrity checksum */ }
            }
            md5 = java.util.Base64.getEncoder().encodeToString(digest.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException failure) { throw unavailable(); }
        var request = new Request.Builder().url(url(p, key)).put(body).header("Content-Type", mime).header("Content-MD5", md5)
                .header(p.provider() == StorageProfile.Provider.ALIYUN_OSS ? "x-oss-meta-sha256" : "x-amz-meta-sha256", hash);
        if (p.provider() == StorageProfile.Provider.ALIYUN_OSS)
            request.header("x-oss-forbid-overwrite", "true").header("x-oss-object-acl", "private");
        else request.header("If-None-Match", "*");
        try (Response response = execute(p, key, request.build(), hash)) {
            if (!response.isSuccessful()) throw unavailable();
        } catch (IOException failure) { throw unavailable(); }
        if (!matches(p, key, size, hash)) throw unavailable();
    }
    public boolean matches(StorageProfile p, String key, long size, String hash) {
        Request request = new Request.Builder().url(url(p, key)).head().build();
        try (Response response = execute(p, key, request, ObjectStorageSigner.EMPTY_HASH)) {
            if (response.code() == 404) return false;
            if (!response.isSuccessful()) throw unavailable();
            String meta = response.header(p.provider() == StorageProfile.Provider.ALIYUN_OSS
                    ? "x-oss-meta-sha256" : "x-amz-meta-sha256");
            if (!Long.toString(size).equals(response.header("Content-Length")) || !hash.equals(meta))
                throw new ApiProblemException(HttpStatus.CONFLICT, "STORAGE_OBJECT_CONFLICT", "归档对象冲突",
                        "对象内容与归档记录不同，已停止写入。", false);
            return true;
        } catch (IOException failure) { throw unavailable(); }
    }
    public InputStream open(StorageProfile p, String key, long start, long length, long total) {
        var request = new Request.Builder().url(url(p, key)).get()
                .header("Accept-Encoding", "identity").header("Range", "bytes=" + start + "-" + (start + length - 1));
        Response response;
        try { response = execute(p, key, request.build(), ObjectStorageSigner.EMPTY_HASH); }
        catch (IOException failure) { throw unavailable(); }
        if (response.code() != 206 || response.body() == null
                || !Long.toString(length).equals(response.header("Content-Length"))
                || !("bytes " + start + "-" + (start + length - 1) + "/" + total).equals(response.header("Content-Range"))) {
            response.close(); throw unavailable();
        }
        return new FilterInputStream(response.body().byteStream()) {
            private long remaining = length;
            @Override public int read() throws IOException {
                byte[] single = new byte[1]; int count = read(single, 0, 1); return count == -1 ? -1 : single[0] & 0xff;
            }
            @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                if (count == 0) return 0;
                if (remaining == 0) return -1;
                int read = in.read(bytes, offset, (int) Math.min(count, remaining));
                if (read < 0) throw new IOException("Object stream ended before recorded size");
                remaining -= read; return read;
            }
            @Override public long skip(long count) throws IOException {
                long skipped = in.skip(Math.min(Math.max(0, count), remaining)); remaining -= skipped; return skipped;
            }
            @Override public int available() throws IOException { return (int) Math.min(in.available(), Math.min(remaining, Integer.MAX_VALUE)); }
            @Override public void close() { response.close(); }
        };
    }
    public void delete(StorageProfile p, String key) {
        try (Response response = execute(p, key, new Request.Builder().url(url(p, key)).delete().build(),
                ObjectStorageSigner.EMPTY_HASH)) {
            if (!response.isSuccessful() && response.code() != 404) throw unavailable();
        } catch (IOException failure) { throw unavailable(); }
    }
    private Response execute(StorageProfile p, String key, Request request, String hash) throws IOException {
        String[] credentials = settings.credentials(p);
        return client.newCall(ObjectStorageSigner.sign(request, p, credentials[0], credentials[1],
                key, hash, clock.instant())).execute();
    }
    private static ApiProblemException unavailable() {
        // Vendor errors and request URLs can include credentials/signatures; never relay them.
        return new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "OBJECT_STORAGE_UNAVAILABLE", "对象存储不可用",
                "请检查存储连接、权限和网络后重试。生成结果仅重试归档，不重新生成。", true);
    }
}
