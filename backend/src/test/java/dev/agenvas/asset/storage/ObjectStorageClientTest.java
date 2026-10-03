package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import okhttp3.Request;

class ObjectStorageClientTest {
    private StorageProfile profile(StorageProfile.Provider provider, boolean path) {
        return new StorageProfile(UUID.randomUUID(), "test", provider, "https://s3.example.com", "us-east-1", "my-bucket", "", path, 1, null, "masked", Instant.EPOCH);
    }
    @Test void privateS3AddressingAndCosVirtualHostsUseExactlyConfiguredDestination() {
        assertThat(ObjectStorageClient.url(profile(StorageProfile.Provider.S3, true), "project/file.png").toString())
                .isEqualTo("https://s3.example.com/my-bucket/project/file.png");
        assertThat(ObjectStorageClient.url(profile(StorageProfile.Provider.TENCENT_COS, false), "project/file.png").toString())
                .isEqualTo("https://my-bucket.s3.example.com/project/file.png");
        for (String key : new String[] { "../secret", "/absolute", "project/../secret", "project/key?token=x" })
            assertThatThrownBy(() -> ObjectStorageClient.url(profile(StorageProfile.Provider.S3, true), key)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void dnsChecksEveryAnswerAndBlocksMetadataLoopbackAndUnspecifiedAddresses() throws Exception {
        for (String address : new String[] { "169.254.169.254", "127.0.0.1", "::1", "0.0.0.0", "224.0.0.1", "fe80::1" }) {
            var dns = ObjectStorageClient.checkedDns(ignored -> List.of(InetAddress.getByName(address)));
            assertThatThrownBy(() -> dns.lookup("store.example.com")).isInstanceOf(java.net.UnknownHostException.class);
        }
        var mixed = ObjectStorageClient.checkedDns(ignored -> List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("169.254.169.254")));
        assertThatThrownBy(() -> mixed.lookup("store.example.com")).isInstanceOf(java.net.UnknownHostException.class);
        var privateDns = ObjectStorageClient.checkedDns(ignored -> List.of(InetAddress.getByName("10.1.2.3")));
        assertThat(privateDns.lookup("minio.example.com")).hasSize(1);
    }
    @Test void s3MatchesAmazonsPublishedHeaderSignatureExample() {
        var s3 = new StorageProfile(UUID.randomUUID(), "example", StorageProfile.Provider.S3,
                "https://s3.amazonaws.com", "us-east-1", "examplebucket", "", false, 1, null, "mask", Instant.EPOCH);
        var request = new Request.Builder().url(ObjectStorageClient.url(s3, "test.txt")).get().header("Range", "bytes=0-9").build();
        var signed = ObjectStorageSigner.sign(request, s3, "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "test.txt", ObjectStorageSigner.EMPTY_HASH, Instant.parse("2013-05-24T00:00:00Z"));
        assertThat(signed.header("Authorization")).endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }
    @Test void ossCanonicalExampleMatchesIndependentHmacCalculation() {
        var oss = new StorageProfile(UUID.randomUUID(), "example", StorageProfile.Provider.ALIYUN_OSS,
                "https://oss-cn-hangzhou.aliyuncs.com", "cn-hangzhou", "examplebucket", "", false, 1, null, "mask", Instant.EPOCH);
        var request = new Request.Builder().url(ObjectStorageClient.url(oss, "exampleobject"))
                .put(okhttp3.RequestBody.create(new byte[3], null)).header("content-disposition", "attachment")
                .header("content-length", "3").header("content-md5", "ICy5YqxZB1uWSwcVLSNLcA==")
                .header("content-type", "text/plain").build();
        var signed = ObjectStorageSigner.sign(request, oss, "example-id", "yourAccessKeySecret", "exampleobject",
                ObjectStorageSigner.EMPTY_HASH, Instant.parse("2025-04-11T06:41:24Z"));
        // Alibaba's documented canonical request hashes to c46d9639...; its displayed derived key
        // differs from its stated secret. This expected HMAC was independently computed in Python.
        assertThat(signed.header("Authorization")).endsWith("Signature=d3694c2dfc5371ee6acd35e88c4871ac95a7ba01d3a2f476768fe61218590097");
    }
    @Test void distinctV4SignaturesAreDeterministicAndContainNoSecret() {
        Instant time = Instant.parse("2025-04-11T06:41:24Z");
        var oss = profile(StorageProfile.Provider.ALIYUN_OSS, false);
        var request = new Request.Builder().url(ObjectStorageClient.url(oss, "exampleobject"))
                .put(okhttp3.RequestBody.create(new byte[0], null)).header("Content-Type", "text/plain").build();
        var signed = ObjectStorageSigner.sign(request, oss, "access-id", "test-secret", "exampleobject", ObjectStorageSigner.EMPTY_HASH, time);
        assertThat(signed.header("Authorization")).startsWith("OSS4-HMAC-SHA256 Credential=access-id/20250411/us-east-1/oss/aliyun_v4_request,Signature=")
                .doesNotContain("test-secret");
        assertThat(signed.header("x-oss-content-sha256")).isEqualTo("UNSIGNED-PAYLOAD");
        var s3 = profile(StorageProfile.Provider.S3, true);
        var aws = ObjectStorageSigner.sign(request, s3, "access-id", "test-secret", "exampleobject", ObjectStorageSigner.EMPTY_HASH, time);
        assertThat(aws.header("Authorization")).contains("AWS4-HMAC-SHA256", "/s3/aws4_request", "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date")
                .doesNotContain("test-secret");
        assertThat(aws.header("x-amz-content-sha256")).isEqualTo(ObjectStorageSigner.EMPTY_HASH);
    }
    @Test void presignedS3GetMatchesThePublishedAwsQuerySignature() {
        // Public AWS documentation's synthetic access key and secret; never a real credential.
        var s3 = new StorageProfile(UUID.randomUUID(), "example", StorageProfile.Provider.S3,
                "https://s3.amazonaws.com", "us-east-1", "examplebucket", "", false, 1, null, "mask", Instant.EPOCH);
        var signed = ObjectStorageSigner.presignGet(ObjectStorageClient.url(s3, "test.txt"), s3,
                "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "test.txt",
                Instant.parse("2013-05-24T00:00:00Z"), 86400);
        assertThat(signed.queryParameter("X-Amz-Signature"))
                .isEqualTo("aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
        assertThat(signed.encodedQuery()).contains("%2F20130524%2Fus-east-1%2Fs3%2Faws4_request");
        assertThat(signed.queryParameter("X-Amz-SignedHeaders")).isEqualTo("host");
    }
    @Test void providerInputsRejectPrivateAndProxyFakeIpsWhileArchiveStillSupportsPrivateStorage() throws Exception {
        for (String ip : new String[] { "127.0.0.1", "10.1.2.3", "169.254.169.254", "198.18.0.1", "fc00::1", "::1", "0.0.0.0" })
            assertThat(ObjectStorageClient.isPublic(InetAddress.getByName(ip))).isFalse();
        assertThat(ObjectStorageClient.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
    }

    @Test void ossPresignedGetMatchesIndependentPythonCanonicalCalculation() {
        var oss = new StorageProfile(UUID.randomUUID(), "example", StorageProfile.Provider.ALIYUN_OSS,
                "https://oss-cn-hangzhou.aliyuncs.com", "cn-hangzhou", "examplebucket", "", false, 1, null, "mask", Instant.EPOCH);
        var signed = ObjectStorageSigner.presignGet(ObjectStorageClient.url(oss, "video.mp4"), oss,
                "example-id", "yourAccessKeySecret", "video.mp4", Instant.parse("2025-04-11T06:41:24Z"), 259200);
        assertThat(signed.queryParameter("x-oss-signature")).isEqualTo("5163a314f07a7242809e18397ad3cdcd9c803a9ae8b3986f71520528d616bd5a");
        assertThat(signed.queryParameter("x-oss-additional-headers")).isEqualTo("host");
        assertThat(signed.queryParameter("x-oss-expires")).isEqualTo("259200");
        assertThat(signed.queryParameter("X-Amz-Signature")).isNull();
    }
    @Test void cosAndPathStyleS3PresignTheirActualHostAndPathAndBoundExpiration() {
        for (var provider : List.of(StorageProfile.Provider.S3, StorageProfile.Provider.TENCENT_COS)) {
            var profile = profile(provider, provider == StorageProfile.Provider.S3);
            var url = ObjectStorageClient.url(profile, "project/video.mp4");
            var signed = ObjectStorageSigner.presignGet(url, profile, "example-id", "synthetic-secret",
                    "project/video.mp4", Instant.EPOCH, 259200);
            assertThat(signed.encodedPath()).isEqualTo(url.encodedPath());
            assertThat(signed.host()).isEqualTo(url.host());
            assertThat(signed.queryParameter("X-Amz-Credential")).isEqualTo("example-id/19700101/us-east-1/s3/aws4_request");
            assertThat(signed.queryParameter("X-Amz-Signature")).matches("[a-f0-9]{64}");
            for (long expires : new long[] { 0, 604801 })
                assertThatThrownBy(() -> ObjectStorageSigner.presignGet(url, profile, "example-id", "synthetic-secret",
                        "project/video.mp4", Instant.EPOCH, expires)).isInstanceOf(IllegalArgumentException.class);
        }
    }

}
