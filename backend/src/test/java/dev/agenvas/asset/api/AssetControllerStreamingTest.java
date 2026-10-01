package dev.agenvas.asset.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.identity.application.AdminPrincipal;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.ResourceHttpMessageConverter;

/** Large private responses stay stream-backed and obey the exact authorized byte interval. */
class AssetControllerStreamingTest {

    @TempDir Path directory;

    @Test
    void largeFileAndRangesNeverMaterializeTheWholeBody() throws Exception {
        long size = 400L * 1024 * 1024;
        Path file = directory.resolve("large-video.mp4");
        // Sparse fixture exercises a realistic maximum-size response without occupying 400 MiB.
        try (RandomAccessFile writable = new RandomAccessFile(file.toFile(), "rw")) {
            writable.setLength(size);
            writable.seek(0);
            writable.write(0x31);
            writable.seek(size - 1);
            writable.write(0x32);
        }
        UUID projectId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "stream-test");
        Asset asset = new Asset(assetId, projectId, Asset.MediaKind.VIDEO,
                "private-test-key", "video/mp4", size, "fixture-hash", 1280, 720,
                1_000,
                "private-poster-key", 1L, "poster-hash", Instant.now());
        AssetService service = mock(AssetService.class);
        var descriptor = new AssetService.AssetContent(asset, asset.objectKey(), size, asset.contentType());
        when(service.content(principal.userId(), projectId, assetId, false)).thenReturn(descriptor);
        var storage = new dev.agenvas.asset.infrastructure.LocalAssetStorage(
                new dev.agenvas.asset.application.AssetProperties(directory), null, null);
        when(service.open(org.mockito.ArgumentMatchers.eq(descriptor), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation ->
                        storage.open("large-video.mp4", invocation.getArgument(1), invocation.getArgument(2), size));
        AssetController controller = new AssetController(service);

        ResponseEntity<InputStreamResource> full = controller.content(
                principal, projectId, assetId, null);
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(full.getHeaders().getContentLength()).isEqualTo(size);
        assertThat(full.getHeaders().getContentType().toString()).isEqualTo("video/mp4");
        assertThat(full.getBody()).isInstanceOf(InputStreamResource.class);
        try (InputStream stream = full.getBody().getInputStream()) {
            assertThat(stream.read()).isEqualTo(0x31);
            assertThat(stream.skip(size)).isEqualTo(size - 1);
            assertThat(stream.read()).isEqualTo(-1);
        }

        CountingBody output = new CountingBody();
        HttpOutputMessage message = new HttpOutputMessage() {
            private final HttpHeaders headers = new HttpHeaders();

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }

            @Override
            public OutputStream getBody() {
                return output;
            }
        };
        // Run this test with -Xmx128m: Spring's actual resource converter must copy the
        // 400 MiB sparse body in bounded chunks rather than buffer it in the JVM.
        new ResourceHttpMessageConverter().write(controller.content(
                principal, projectId, assetId, null).getBody(), assetContentType(), message);
        assertThat(output.count).isEqualTo(size);
        assertThat(output.largestWrite).isLessThanOrEqualTo(64 * 1024);
        assertThat(output.first).isEqualTo(0x31);
        assertThat(output.last).isEqualTo(0x32);

        ResponseEntity<InputStreamResource> tail = controller.content(
                principal, projectId, assetId, "bytes=" + (size - 2) + "-");
        assertThat(tail.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(tail.getHeaders().getContentLength()).isEqualTo(2);
        assertThat(tail.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes " + (size - 2) + "-" + (size - 1) + "/" + size);
        try (InputStream stream = tail.getBody().getInputStream()) {
            assertThat(stream.readAllBytes()).containsExactly((byte) 0, (byte) 0x32);
        }

        ResponseEntity<InputStreamResource> shortRange = controller.content(
                principal, projectId, assetId, "bytes=0-1");
        try (InputStream stream = shortRange.getBody().getInputStream()) {
            assertThat(stream.read()).isEqualTo(0x31);
            assertThat(stream.skip(size)).isEqualTo(1);
            assertThat(stream.read()).isEqualTo(-1);
        }

        ResponseEntity<InputStreamResource> head = controller.head(
                principal, projectId, assetId, "bytes=0-1");
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(head.getHeaders().getContentLength()).isEqualTo(2);
        assertThat(head.getBody()).isNull();
    }

    private static org.springframework.http.MediaType assetContentType() {
        return org.springframework.http.MediaType.parseMediaType("video/mp4");
    }

    /** Discards streamed bytes while recording copy size and boundary content. */
    private static final class CountingBody extends OutputStream {
        private long count;
        private int largestWrite;
        private int first = -1;
        private int last = -1;

        @Override
        public void write(int value) throws IOException {
            if (first < 0) first = value & 0xff;
            last = value & 0xff;
            count++;
            largestWrite = Math.max(largestWrite, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return;
            if (first < 0) first = bytes[offset] & 0xff;
            last = bytes[offset + length - 1] & 0xff;
            count += length;
            largestWrite = Math.max(largestWrite, length);
        }
    }
}
