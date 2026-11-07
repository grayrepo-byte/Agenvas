package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpStoragePolicyTest {
    @Test void objectStorageAndWebdavCanUseLocalhostWithoutAnAddressException() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
        server.start();
        var settings = mock(StorageSettingsService.class);
        try {
            String endpoint = "http://localhost:" + server.getAddress().getPort();
            var s3 = new StorageProfile(UUID.randomUUID(), "Synthetic", StorageProfile.Provider.S3,
                    endpoint, "us-east-1", "bucket", "", true, 1, null, "mask", Instant.EPOCH);
            var dav = new StorageProfile(UUID.randomUUID(), "Synthetic", StorageProfile.Provider.WEBDAV,
                    endpoint + "/dav", "", "", "", false, 1, null, "mask", Instant.EPOCH);
            when(settings.credentials(s3)).thenReturn(new String[] {"synthetic-id", "synthetic-secret"});
            when(settings.credentials(dav)).thenReturn(new String[] {"synthetic-user", "synthetic-password"});
            assertThat(new ObjectStorageClient(settings, Clock.systemUTC()).matches(s3, "key", 1, "hash")).isFalse();
            assertThat(new WebDavStorageClient(settings).matches(dav, "key", 1, "hash")).isFalse();
            assertThat(new ObjectStorageClient(settings, Clock.systemUTC()).signedGet(s3, "key", java.time.Duration.ofMinutes(5)))
                    .startsWith(endpoint + "/bucket/key?");
        } finally { server.stop(0); }
    }
}
