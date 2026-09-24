package dev.agenvas.asset.api;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Authenticated image upload and private synchronous streaming with single byte-range support. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/assets")
public class AssetController {

    private final AssetService assets;

    public AssetController(AssetService assets) {
        this.assets = assets;
    }

    /** Client filename and declared MIME are intentionally ignored. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AssetResponse> upload(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestPart("file") MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            Asset asset = assets.archiveImage(principal.userId(), projectId, input);
            return ResponseEntity.status(HttpStatus.CREATED).body(AssetResponse.from(asset));
        }
    }

    /** Sends a bounded range without loading the media into JVM memory. */
    @GetMapping("/{assetId}/content")
    public ResponseEntity<InputStreamResource> content(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID assetId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) throws IOException {
        return stream(principal, projectId, assetId, range, false);
    }

    /** Provides the same headers as GET while leaving the response body empty. */
    @RequestMapping(path = "/{assetId}/content", method = RequestMethod.HEAD)
    public ResponseEntity<InputStreamResource> head(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID assetId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) throws IOException {
        return stream(principal, projectId, assetId, range, true);
    }

    /** Serves only the precomputed bounded preview, never decoding the original on GET. */
    @GetMapping("/{assetId}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID assetId) throws IOException {
        AssetService.ThumbnailFile preview = assets.getThumbnail(
                principal.userId(), projectId, assetId);
        long size = preview.asset().thumbnailByteSize();
        if (size < 1 || size > 20L * 1024 * 1024) {
            throw new IOException("Archived thumbnail has an invalid size");
        }
        byte[] bytes = new byte[(int) size];
        try (FileChannel channel = FileChannel.open(preview.path(),
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) {
                    throw new IOException("Archived thumbnail ended before its recorded size");
                }
            }
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .contentLength(bytes.length)
                .body(bytes);
    }

    private ResponseEntity<InputStreamResource> stream(AdminPrincipal principal,
            UUID projectId, UUID assetId, String requestedRange, boolean head) throws IOException {
        AssetService.AssetFile file = assets.get(principal.userId(), projectId, assetId);
        return streamFile(file.path(), file.asset().byteSize(),
                file.asset().contentType(), requestedRange, head);
    }

    private ResponseEntity<InputStreamResource> streamFile(Path path, long size,
            String contentType, String requestedRange, boolean head) throws IOException {
        ByteRange selected = ByteRange.parse(requestedRange, size);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        headers.setContentLength(selected.length());
        if (selected.partial()) {
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + selected.start() + "-" + selected.end() + "/" + size);
        }
        InputStreamResource body = head ? null : new InputStreamResource(
                boundedFileRange(path, selected));
        return new ResponseEntity<>(body, headers,
                selected.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    }

    /** Opens without symlink traversal and exposes at most the approved byte interval. */
    private InputStream boundedFileRange(Path path, ByteRange selected) throws IOException {
        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        channel.position(selected.start());
        return new FilterInputStream(Channels.newInputStream(channel)) {
            private long remaining = selected.length();

            @Override
            public int read() throws IOException {
                if (remaining == 0) return -1;
                int value = super.read();
                if (value < 0) throw new IOException("Archived asset ended before its recorded size");
                remaining--;
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                if (remaining == 0) return -1;
                int count = in.read(bytes, offset, (int) Math.min(length, remaining));
                if (count <= 0) throw new IOException("Archived asset ended before its recorded size");
                remaining -= count;
                return count;
            }
        };
    }

    /** Range failures carry the mandatory unsatisfied Content-Range size hint. */
    @ExceptionHandler(InvalidRange.class)
    public ResponseEntity<ProblemDetail> invalidRange(InvalidRange exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "请求的字节范围不可用。");
        problem.setType(URI.create("urn:agenvas:problem:asset-range-invalid"));
        problem.setTitle("范围无效");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "ASSET_RANGE_INVALID");
        problem.setProperty("retryable", false);
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + exception.size)
                .body(problem);
    }

    /** Public metadata contains no filesystem path or untrusted provider URL. */
    public record AssetResponse(UUID id, UUID projectId, Asset.MediaKind mediaKind,
            String contentType, long byteSize, String sha256, Integer width,
            Integer height, Instant createdAt) {

        public static AssetResponse from(Asset asset) {
            return new AssetResponse(asset.id(), asset.projectId(), asset.mediaKind(),
                    asset.contentType(), asset.byteSize(), asset.sha256(), asset.width(),
                    asset.height(), asset.createdAt());
        }
    }

    /** Strictly accepts a single satisfiable RFC 7233 byte range. */
    record ByteRange(long start, long end, boolean partial) {

        long length() {
            return end - start + 1;
        }

        static ByteRange parse(String header, long size) {
            if (header == null || header.isBlank()) {
                return new ByteRange(0, size - 1, false);
            }
            if (!header.startsWith("bytes=") || header.indexOf(',', 6) >= 0) {
                throw new InvalidRange(size);
            }
            String value = header.substring(6);
            int dash = value.indexOf('-');
            if (dash < 0 || dash != value.lastIndexOf('-')) {
                throw new InvalidRange(size);
            }
            try {
                long start;
                long end;
                if (dash == 0) {
                    long suffix = Long.parseLong(value.substring(1));
                    if (suffix < 1) {
                        throw new InvalidRange(size);
                    }
                    start = Math.max(0, size - suffix);
                    end = size - 1;
                } else {
                    start = Long.parseLong(value.substring(0, dash));
                    end = dash == value.length() - 1
                            ? size - 1 : Long.parseLong(value.substring(dash + 1));
                }
                if (start < 0 || start >= size || end < start || end >= size) {
                    throw new InvalidRange(size);
                }
                return new ByteRange(start, end, true);
            } catch (NumberFormatException exception) {
                throw new InvalidRange(size);
            }
        }
    }

    /** Carries the authenticated file length without exposing its filesystem location. */
    private static final class InvalidRange extends RuntimeException {
        private final long size;

        private InvalidRange(long size) {
            this.size = size;
        }
    }
}
