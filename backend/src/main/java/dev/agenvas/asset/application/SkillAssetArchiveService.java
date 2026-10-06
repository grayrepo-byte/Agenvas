package dev.agenvas.asset.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.PrivateMediaArchive.Media;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.library.application.LibraryService.PinnedSkillAsset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Trusted immutable-media boundary for Skill publication, direct reading and legacy file cleanup.
 * The Skill module owns persistent operation IDs, lease fencing and cleanup records;
 * this service archives verified bytes without creating project content.
 */
@Service
public class SkillAssetArchiveService {
    private final PrivateMediaArchive archive;
    private final AssetService assets;

    public SkillAssetArchiveService(PrivateMediaArchive archive, AssetService assets) {
        this.archive = archive;
        this.assets = assets;
    }

    /** Copies outside business transactions. Stable operation IDs recover the same archive. */
    public Media archivePinned(UUID owner, UUID fileId, PinnedSkillAsset pinned) {
        requireOwnedImage(owner, pinned.media());
        if (pinned.kind() != Artifact.Kind.IMAGE || !pinned.contentHash().equals(pinned.media().sha256()))
            throw new IllegalStateException("Skill source snapshot differs from verified media");
        Media copied = archive.archive(owner, fileId, Asset.MediaKind.IMAGE,
                archive.pinned(owner, pinned.pinKey()));
        requireHash(copied.sha256(), pinned.contentHash());
        return copied;
    }

    /** Internal streaming seam after the calling Skill service has authorized its version. */
    public LibraryService.MediaFile file(UUID owner, Media media, boolean thumbnail) {
        requireOwnedImage(owner, media);
        return new LibraryService.MediaFile(archive.file(owner, media, thumbnail),
                thumbnail ? "image/png" : media.contentType(),
                thumbnail ? media.thumbnailByteSize() : media.byteSize());
    }

    /** Cleanup must be called only for an operation eligible under its persistent lease. */
    public void discardPin(UUID owner, String pinKey) { archive.discardPin(owner, pinKey); }

    public void discardArchive(UUID owner, Media media) {
        requireOwnedImage(owner, media);
        archive.discard(owner, media);
    }

    /** Persistent cleanup jobs may finish idempotently without deleting registered content. */
    public boolean cleanupPreparedProjectImport(UUID owner, Asset prepared) {
        if (prepared.mediaKind() != Asset.MediaKind.IMAGE)
            throw new IllegalStateException("Only image Skill installation is supported");
        return assets.cleanupPreparedImport(owner, prepared);
    }

    public boolean cleanupPlannedSkillImport(UUID owner, UUID project, UUID assetId) {
        return assets.cleanupPlannedSkillImport(owner, project, assetId);
    }

    private void requireOwnedImage(UUID owner, Media media) {
        if (!owner.equals(media.ownerId()) || media.kind() != Asset.MediaKind.IMAGE)
            throw new IllegalStateException("Skill archive owner or kind mismatch");
    }

    private void requireHash(String actual, String expected) {
        if (!actual.equals(expected))
            throw new ApiProblemException(HttpStatus.CONFLICT, "SKILL_ASSET_HASH_MISMATCH",
                    ApiMessage.of("api.skill-asset-archive.invalid-source"),
                    ApiMessage.of("api.skill-asset-archive.hash-mismatch"), false);
    }
}
