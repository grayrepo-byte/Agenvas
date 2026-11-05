package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpStoragePolicyTest {
    @Test void sensitiveLiteralTargetsAreRejectedBeforeCredentialsOrNetworkDispatch() {
        var settings = mock(StorageSettingsService.class);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var http = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet(); throw new AssertionError("Blocked target must not be dispatched");
        }).build();
        for (String host : List.of("127.0.0.1", "169.254.169.254", "[fd00:ec2::254]", "[fd20:ce::254]", "168.63.129.16")) {
            var s3 = new StorageProfile(UUID.randomUUID(), "Synthetic", StorageProfile.Provider.S3,
                    "http://" + host + ":9000", "us-east-1", "bucket", "", true, 1, null, "mask", Instant.EPOCH);
            var dav = new StorageProfile(UUID.randomUUID(), "Synthetic", StorageProfile.Provider.WEBDAV,
                    "http://" + host + ":9000/dav", "", "", "", false, 1, null, "mask", Instant.EPOCH);
            assertThatThrownBy(() -> new ObjectStorageClient(settings, Clock.systemUTC(), http).matches(s3, "key", 1, "hash"))
                    .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
            assertThatThrownBy(() -> new WebDavStorageClient(settings, http).matches(dav, "key", 1, "hash"))
                    .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        }
        verifyNoInteractions(settings);
        assertThat(requests).hasValue(0);
    }

    @Test void storageDnsPermitsPublicAndPrivateAnswersButNeverMixedSensitiveAnswers() throws Exception {
        var valid = List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.5"));
        assertThat(ObjectStorageClient.checkedDns(host -> valid).lookup("bucket.store.invalid")).containsExactlyElementsOf(valid);
        for (String blocked : List.of("fd00:ec2::254", "fd20:ce::254", "168.63.129.16", "100.100.100.200", "127.0.0.1"))
            assertThatThrownBy(() -> ObjectStorageClient.checkedDns(host -> List.of(valid.getFirst(), InetAddress.getByName(blocked)))
                    .lookup("bucket.store.invalid")).isInstanceOf(java.net.UnknownHostException.class);
    }
}
