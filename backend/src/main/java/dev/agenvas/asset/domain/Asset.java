package dev.agenvas.asset.domain;

import java.time.Instant;
import java.util.UUID;

/** Database identity and verified metadata for one private, immutable media file. */
public record Asset(UUID id, UUID projectId, MediaKind mediaKind, String objectKey,
        String contentType, long byteSize, String sha256, Integer width, Integer height,
        String thumbnailKey, Long thumbnailByteSize, String thumbnailSha256,
        Instant createdAt) {

    /** The file formats retained by the local archive. */
    public enum MediaKind { IMAGE, VIDEO }
}
