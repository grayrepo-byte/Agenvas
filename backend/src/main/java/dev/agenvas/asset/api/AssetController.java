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

/** 提供鉴权图片上传、私有媒体流和单字节范围读取，不向客户端暴露磁盘路径。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/assets")
public class AssetController {

    /** 校验媒体内容、检查项目权限并读取归档文件。 */
    private final AssetService assets;

    /** 注入资产归档和读取服务。 */
    public AssetController(AssetService assets) {
        this.assets = assets;
    }

    /** 忽略客户端文件名和声明 MIME，由服务端检查字节内容后归档。 */
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

    /** 返回经项目权限检查的不可变媒体元数据，不暴露本地对象键。 */
    @GetMapping("/{assetId}")
    public AssetResponse metadata(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID assetId) {
        return AssetResponse.from(assets.get(principal.userId(), projectId, assetId).asset());
    }

    /** 流式发送完整内容或受限字节范围，不把媒体文件整体读入 JVM 内存。 */
    @GetMapping("/{assetId}/content")
    public ResponseEntity<InputStreamResource> content(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID assetId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) throws IOException {
        return stream(principal, projectId, assetId, range, false);
    }

    /** 返回与 GET 相同的状态和范围响应头，但不打开媒体响应体。 */
    @RequestMapping(path = "/{assetId}/content", method = RequestMethod.HEAD)
    public ResponseEntity<InputStreamResource> head(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID assetId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) throws IOException {
        return stream(principal, projectId, assetId, range, true);
    }

    /** 仅读取已预生成的视频封面帧，不在 GET 请求中解码原视频；图片素材返回 404。 */
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

    /** 先取得经授权的资产文件，再使用记录的大小与类型构造流式响应。 */
    private ResponseEntity<InputStreamResource> stream(AdminPrincipal principal,
            UUID projectId, UUID assetId, String requestedRange, boolean head) throws IOException {
        AssetService.AssetFile file = assets.get(principal.userId(), projectId, assetId);
        return streamFile(file.path(), file.asset().byteSize(),
                file.asset().contentType(), requestedRange, head);
    }

    /** 根据单段范围请求构造 200/206 响应，并为 HEAD 留空响应体。 */
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

    /** 禁止符号链接跟随，并将输入流严格限制在已解析的字节区间内。 */
    private InputStream boundedFileRange(Path path, ByteRange selected) throws IOException {
        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        channel.position(selected.start());
        return new FilterInputStream(Channels.newInputStream(channel)) {
            private long remaining = selected.length();

            /** 读取单字节并维护范围剩余长度，文件意外提前结束时报错。 */
            @Override
            public int read() throws IOException {
                if (remaining == 0) return -1;
                int value = super.read();
                if (value < 0) throw new IOException("Archived asset ended before its recorded size");
                remaining--;
                return value;
            }

            /** 限制批量读取长度，不允许越过所选范围末端。 */
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                if (remaining == 0) return -1;
                int count = in.read(bytes, offset, (int) Math.min(length, remaining));
                if (count <= 0) throw new IOException("Archived asset ended before its recorded size");
                remaining -= count;
                return count;
            }

            /** 通过文件通道前移位置并同步扣减可读取范围。 */
            @Override
            public long skip(long count) throws IOException {
                if (count <= 0 || remaining == 0) return 0;
                long skipped = Math.min(count, remaining);
                channel.position(channel.position() + skipped);
                remaining -= skipped;
                return skipped;
            }

            /** 返回不超过当前范围剩余量的可立即读取字节数。 */
            @Override
            public int available() throws IOException {
                return (int) Math.min(in.available(), Math.min(remaining, Integer.MAX_VALUE));
            }
        };
    }

    /** 为范围错误返回 416，并附带客户端重新请求所需的文件总长度。 */
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

    /** 对外媒体元数据，不包含文件系统路径或不可信 Provider URL。
     * @param id 资产 ID
     * @param projectId 所属项目 ID
     * @param mediaKind 媒体类别
     * @param contentType 服务端识别的媒体类型
     * @param byteSize 文件字节数
     * @param sha256 文件内容摘要
     * @param width 图像宽度；非图像时为空
     * @param height 图像高度；非图像时为空
     * @param durationMs 视频时长；非视频时为空
     * @param createdAt 归档时间
     */
    public record AssetResponse(UUID id, UUID projectId, Asset.MediaKind mediaKind,
            String contentType, long byteSize, String sha256, Integer width,
            Integer height, Integer durationMs, Instant createdAt) {

        /** 将资产领域对象投影为不含存储位置的公开响应。 */
        public static AssetResponse from(Asset asset) {
            return new AssetResponse(asset.id(), asset.projectId(), asset.mediaKind(),
                    asset.contentType(), asset.byteSize(), asset.sha256(), asset.width(),
                    asset.height(), asset.durationMs(), asset.createdAt());
        }
    }

    /** 一个已解析的单段字节范围，start/end 均为包含端点。
     * @param start 起始字节位置
     * @param end 结束字节位置
     * @param partial 是否为客户端显式请求的部分内容
     */
    record ByteRange(long start, long end, boolean partial) {

        /** 返回包含起止字节后的总长度。 */
        long length() {
            return end - start + 1;
        }

        /** 解析单个 RFC 字节范围；拒绝多段、不满足或超出文件边界的请求。 */
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

    /** 供异常处理器构造 Content-Range 提示，仅携带文件长度。 */
    private static final class InvalidRange extends RuntimeException {
        /** 原文件长度，用于生成 416 响应的未满足范围总长提示。 */
        private final long size;

        /** 保留原文件长度供 416 响应生成器使用。 */
        private InvalidRange(long size) {
            this.size = size;
        }
    }
}
