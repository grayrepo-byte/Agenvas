package dev.agenvas.asset.storage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.HttpUrl;
import okhttp3.Request;

/** Fixed object operations use S3 SigV4 (including COS), or OSS's distinct V4 protocol. */
enum ObjectStorageSigner {
    SIG_V4("AWS4", "AWS4-HMAC-SHA256", "s3", "aws4_request", "x-amz-",
            new QueryNames("X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date", "X-Amz-Expires",
                    "X-Amz-SignedHeaders", "X-Amz-Signature")) {
        @Override
        String canonicalPath(HttpUrl url, StorageProfile profile, String key) {
            return url.encodedPath();
        }
        @Override
        String payload(String digest) {
            return digest;
        }
        @Override
        String completeSignedHeaders(HttpUrl url, TreeMap<String, String> signed, SortedSet<String> additional) {
            signed.put("host", host(url));
            return String.join(";", signed.keySet());
        }
        @Override
        String authorizationSuffix(String names, String signature) {
            return ", SignedHeaders=" + names + ", Signature=" + signature;
        }
        @Override
        void protectPut(Request.Builder request) {
            request.header("If-None-Match", "*");
        }
    },
    OSS_V4("aliyun_v4", "OSS4-HMAC-SHA256", "oss", "aliyun_v4_request", "x-oss-",
            new QueryNames("x-oss-signature-version", "x-oss-credential", "x-oss-date", "x-oss-expires",
                    "x-oss-additional-headers", "x-oss-signature")) {
        @Override
        String canonicalPath(HttpUrl url, StorageProfile profile, String key) {
            return "/" + profile.bucket() + "/" + key;
        }
        @Override
        String payload(String digest) {
            return UNSIGNED_PAYLOAD;
        }
        @Override
        String completeSignedHeaders(HttpUrl url, TreeMap<String, String> signed, SortedSet<String> additional) {
            return String.join(";", additional);
        }
        @Override
        String authorizationSuffix(String names, String signature) {
            return (names.isEmpty() ? "" : ",AdditionalHeaders=" + names) + ",Signature=" + signature;
        }
        @Override
        void protectPut(Request.Builder request) {
            request.header("x-oss-forbid-overwrite", "true").header("x-oss-object-acl", "private");
        }
    };
    static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    static final String EMPTY_HASH = hash(new byte[0]);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private record QueryNames(String algorithm, String credential, String date, String expires,
            String signedHeaders, String signature) {
    }
    private final String secretPrefix;
    private final String algorithm;
    private final String service;
    private final String terminal;
    private final String headerPrefix;
    private final QueryNames queryNames;

    ObjectStorageSigner(String secretPrefix, String algorithm, String service, String terminal,
            String headerPrefix, QueryNames queryNames) {
        this.secretPrefix = secretPrefix;
        this.algorithm = algorithm;
        this.service = service;
        this.terminal = terminal;
        this.headerPrefix = headerPrefix;
        this.queryNames = queryNames;
    }

    static ObjectStorageSigner forProvider(StorageProfile.Provider provider) {
        return switch (provider) {
            case S3, TENCENT_COS -> SIG_V4;
            case ALIYUN_OSS -> OSS_V4;
            case SMB, WEBDAV -> throw new IllegalArgumentException("File protocols cannot use object-store signing");
        };
    }
    String metadataHeader() {
        return headerPrefix + "meta-sha256";
    }
    abstract void protectPut(Request.Builder request);
    abstract String canonicalPath(HttpUrl url, StorageProfile profile, String key);
    abstract String payload(String digest);
    abstract String completeSignedHeaders(HttpUrl url, TreeMap<String, String> signed, SortedSet<String> additional);
    abstract String authorizationSuffix(String names, String signature);

    static Request sign(Request request, StorageProfile profile, String id, String secret,
            String objectKey, String payloadHash, Instant instant) {
        var protocol = forProvider(profile.provider());
        String timestamp = TIMESTAMP.format(instant);
        String date = timestamp.substring(0, 8);
        String hash = protocol.payload(payloadHash);
        var builder = request.newBuilder().header(protocol.headerPrefix + "date", timestamp)
                .header(protocol.headerPrefix + "content-sha256", hash);
        Request unsigned = builder.build();
        TreeMap<String, String> signed = new TreeMap<>();
        for (String name : unsigned.headers().names()) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith(protocol.headerPrefix) || lower.equals("content-type") || lower.equals("content-md5"))
                signed.put(lower, unsigned.header(name).trim());
        }
        var additional = new TreeSet<String>();
        for (String name : new String[]{"range", "content-length", "content-disposition"}) {
            String value = unsigned.header(name);
            if (value != null) {
                signed.put(name, value.trim());
                additional.add(name);
            }
        }
        String names = protocol.completeSignedHeaders(request.url(), signed, additional);
        StringBuilder canonicalHeaders = new StringBuilder();
        signed.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));
        String path = protocol.canonicalPath(request.url(), profile, objectKey);
        // Keys and prefixes are restricted to safe ASCII path characters, so encoding is stable.
        String canonical = request.method() + "\n" + path + "\n\n" + canonicalHeaders + "\n"
                + names + "\n" + hash;
        String scope = protocol.scope(date, profile.region());
        String signature = protocol.signature(canonical, secret, timestamp, date, profile.region(), scope);
        String authorization = protocol.algorithm + " Credential=" + id + "/" + scope
                + protocol.authorizationSuffix(names, signature);
        return builder.header("Authorization", authorization).build();
    }
    /** A GET bearer URL signs only Host, so provider GET/Range requests need no extra headers. */
    static HttpUrl presignGet(HttpUrl url, StorageProfile profile, String id, String secret,
            String objectKey, Instant instant, long expiresSeconds) {
        if (expiresSeconds < 1 || expiresSeconds > java.time.Duration.ofDays(7).toSeconds())
            throw new IllegalArgumentException("Invalid signed URL lifetime");
        var protocol = forProvider(profile.provider());
        String timestamp = TIMESTAMP.format(instant);
        String date = timestamp.substring(0, 8);
        String scope = protocol.scope(date, profile.region());
        TreeMap<String, String> query = new TreeMap<>();
        query.put(protocol.queryNames.algorithm(), protocol.algorithm);
        query.put(protocol.queryNames.credential(), id + "/" + scope);
        query.put(protocol.queryNames.date(), timestamp);
        query.put(protocol.queryNames.expires(), Long.toString(expiresSeconds));
        query.put(protocol.queryNames.signedHeaders(), "host");
        var builder = url.newBuilder();
        query.forEach((name, value) -> builder.addEncodedQueryParameter(name, encode(value)));
        HttpUrl unsigned = builder.build();
        String path = protocol.canonicalPath(url, profile, objectKey);
        String canonical = "GET\n" + path + "\n" + unsigned.encodedQuery()
                + "\nhost:" + host(url) + "\n\nhost\n" + UNSIGNED_PAYLOAD;
        return builder.addQueryParameter(protocol.queryNames.signature(),
                protocol.signature(canonical, secret, timestamp, date, profile.region(), scope)).build();
    }

    private String scope(String date, String region) {
        return date + "/" + region + "/" + service + "/" + terminal;
    }

    private String signature(String canonical, String secret, String timestamp, String date, String region,
            String scope) {
        String toSign = algorithm + "\n" + timestamp + "\n" + scope + "\n"
                + hash(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac((secretPrefix + secret).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, terminal);
        return HexFormat.of().formatHex(hmac(key, toSign));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("*", "%2A").replace("%7E", "~");
    }

    private static String host(HttpUrl url) {
        String hostname = url.host().contains(":") ? "[" + url.host() + "]" : url.host();
        return hostname + (url.port() == HttpUrl.defaultPort(url.scheme()) ? "" : ":" + url.port());
    }
    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC unavailable", failure);
        }
    }
}
