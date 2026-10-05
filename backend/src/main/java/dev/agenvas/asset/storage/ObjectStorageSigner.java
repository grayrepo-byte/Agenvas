package dev.agenvas.asset.storage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.HttpUrl;
import okhttp3.Request;

/** Fixed object operations use S3 SigV4 (including COS), or OSS's distinct V4 protocol. */
final class ObjectStorageSigner {
    static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    static final String EMPTY_HASH = hash(new byte[0]);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private ObjectStorageSigner() {}

    static Request sign(Request request, StorageProfile profile, String id, String secret,
            String objectKey, String payloadHash, Instant instant) {
        boolean oss = profile.provider() == StorageProfile.Provider.ALIYUN_OSS;
        String timestamp = TIMESTAMP.format(instant);
        String date = timestamp.substring(0, 8);
        String headerPrefix = oss ? "x-oss-" : "x-amz-";
        String algorithm = oss ? "OSS4-HMAC-SHA256" : "AWS4-HMAC-SHA256";
        String service = oss ? "oss" : "s3";
        String terminal = oss ? "aliyun_v4_request" : "aws4_request";
        String hash = oss ? UNSIGNED_PAYLOAD : payloadHash;
        var builder = request.newBuilder().header(headerPrefix + "date", timestamp)
                .header(headerPrefix + "content-sha256", hash);
        Request unsigned = builder.build();
        TreeMap<String, String> signed = new TreeMap<>();
        for (String name : unsigned.headers().names()) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith(headerPrefix) || lower.equals("content-type") || lower.equals("content-md5"))
                signed.put(lower, unsigned.header(name).trim());
        }
        var additional = new java.util.TreeSet<String>();
        for (String name : new String[] { "range", "content-length", "content-disposition" }) {
            String value = unsigned.header(name);
            if (value != null) { signed.put(name, value.trim()); if (oss) additional.add(name); }
        }
        if (!oss) signed.put("host", host(request.url()));
        StringBuilder canonicalHeaders = new StringBuilder();
        signed.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));
        String signedNames = oss ? String.join(";", additional) : String.join(";", signed.keySet());
        String path = oss ? "/" + profile.bucket() + "/" + objectKey : request.url().encodedPath();
        // Keys and prefixes are restricted to safe ASCII path characters, so encoding is stable.
        String canonical = request.method() + "\n" + path + "\n\n" + canonicalHeaders + "\n"
                + signedNames + "\n" + hash;
        String scope = date + "/" + profile.region() + "/" + service + "/" + terminal;
        String toSign = algorithm + "\n" + timestamp + "\n" + scope + "\n" + hash(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(((oss ? "aliyun_v4" : "AWS4") + secret).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, profile.region()); key = hmac(key, service); key = hmac(key, terminal);
        String signature = HexFormat.of().formatHex(hmac(key, toSign));
        String authorization = algorithm + " Credential=" + id + "/" + scope
                + (oss ? (additional.isEmpty() ? "" : ",AdditionalHeaders=" + signedNames) + ",Signature=" : ", SignedHeaders=" + signedNames + ", Signature=") + signature;
        return builder.header("Authorization", authorization).build();
    }
    /** A GET bearer URL signs only Host, so provider GET/Range requests need no extra headers. */
    static HttpUrl presignGet(HttpUrl url, StorageProfile profile, String id, String secret,
            String objectKey, Instant instant, long expiresSeconds) {
        if (expiresSeconds < 1 || expiresSeconds > java.time.Duration.ofDays(7).toSeconds())
            throw new IllegalArgumentException("Invalid signed URL lifetime");
        boolean oss = profile.provider() == StorageProfile.Provider.ALIYUN_OSS;
        String timestamp = TIMESTAMP.format(instant);
        String date = timestamp.substring(0, 8);
        String service = oss ? "oss" : "s3";
        String terminal = oss ? "aliyun_v4_request" : "aws4_request";
        String algorithm = oss ? "OSS4-HMAC-SHA256" : "AWS4-HMAC-SHA256";
        String scope = date + "/" + profile.region() + "/" + service + "/" + terminal;
        TreeMap<String, String> query = new TreeMap<>();
        query.put(oss ? "x-oss-signature-version" : "X-Amz-Algorithm", algorithm);
        query.put(oss ? "x-oss-credential" : "X-Amz-Credential", id + "/" + scope);
        query.put(oss ? "x-oss-date" : "X-Amz-Date", timestamp);
        query.put(oss ? "x-oss-expires" : "X-Amz-Expires", Long.toString(expiresSeconds));
        query.put(oss ? "x-oss-additional-headers" : "X-Amz-SignedHeaders", "host");
        var builder = url.newBuilder();
        query.forEach((name, value) -> builder.addEncodedQueryParameter(name, encode(value)));
        HttpUrl unsigned = builder.build();
        String path = oss ? "/" + profile.bucket() + "/" + objectKey : url.encodedPath();
        String canonical = "GET\n" + path + "\n" + unsigned.encodedQuery()
                + "\nhost:" + host(url) + "\n\nhost\n" + UNSIGNED_PAYLOAD;
        String toSign = algorithm + "\n" + timestamp + "\n" + scope + "\n"
                + hash(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(((oss ? "aliyun_v4" : "AWS4") + secret).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, profile.region()); key = hmac(key, service); key = hmac(key, terminal);
        return builder.addQueryParameter(oss ? "x-oss-signature" : "X-Amz-Signature",
                HexFormat.of().formatHex(hmac(key, toSign))).build();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("*", "%2A").replace("%7E", "~");
    }

    private static String host(HttpUrl url) {
        return url.host() + (url.port() == 443 || url.port() == 80 ? "" : ":" + url.port());
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (GeneralSecurityException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("HMAC unavailable", failure); }
    }
}
