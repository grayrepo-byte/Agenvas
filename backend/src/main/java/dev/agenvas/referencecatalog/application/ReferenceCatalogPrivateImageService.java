package dev.agenvas.referencecatalog.application;

import static dev.agenvas.referencecatalog.application.ReferenceCatalogValidation.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalogPrivatePolicy;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.SourceType;
import dev.agenvas.referencecatalog.infrastructure.*;
import dev.agenvas.referencecatalog.infrastructure.ReferenceCatalogPrivateImageRepository.Image;
import dev.agenvas.shared.crypto.Sha256;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** An explicit one-image private reference copy is independent of bulk acquisition or distribution consent. */
@Service
public class ReferenceCatalogPrivateImageService {
    private final ReferenceCatalogService catalog;
    private final ReferenceCatalogPrivateImageRepository repository;
    private final ReferenceCatalogGitHubClient github;
    private final ReferenceCatalogImageCache cache;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final boolean recovery;
    private final ReferenceCatalogSiteClient site;
    public ReferenceCatalogPrivateImageService(ReferenceCatalogService catalog,
            ReferenceCatalogPrivateImageRepository repository, ReferenceCatalogGitHubClient github,
            ReferenceCatalogSiteClient site, ReferenceCatalogImageCache cache,
            Clock clock, ObjectMapper mapper, PlatformTransactionManager transactions,
            @Value("${agenvas.recovery-mode:false}") boolean recovery) {
        this.catalog = catalog;
        this.repository = repository;
        this.github = github;
        this.site = site;
        this.cache = cache;
        this.clock = clock;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactions);
        this.recovery = recovery;
    }
    public record PrivateImage(UUID id, String sourceId, UUID snapshotId, String entryId, String variantId,
            String sha256, long byteSize, String contentType, int width, int height, String previewUrl,
            boolean cached) {
    }
    public record SelectionRequest(String sourceId, UUID snapshotId, String entryId, String variantId) {
    }
    public PrivateImage acquire(UUID owner, String source, UUID snapshot, String entry, String variant, String key) {
        writable();
        slug(source);
        slug(entry);
        slug(variant);
        if (snapshot == null || key == null || key.isBlank() || key.length() > 200)
            throw invalid();
        String hash = Sha256.hex(mapper.writeValueAsString(new SelectionRequest(source, snapshot, entry, variant)));
        Image accepted = tx.execute(ignored -> {
            repository.lockAcceptance();
            var replay = repository.command(owner, key);
            if (replay.isPresent()) {
                if (!hash.equals(replay.get().payloadHash()))
                    throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                            "api.reference-catalog.idempotencyConflict");
                return owned(owner, replay.get().imageId());
            }
            var selected = allowed(source, snapshot, entry, variant);
            Image image = repository.selection(owner, snapshot, entry, variant).orElseGet(() -> {
                Image created = new Image(
                        UUID.randomUUID(), owner, source, snapshot, entry, variant, ReferenceCatalogPrivatePolicy
                                .archivePath(selected.source(), selected.entry(), selected.variant()),
                        null, null, null, null, null, false);
                repository.insert(created, clock.instant());
                return created;
            });
            repository.insertCommand(owner, key, hash, image.id(), clock.instant());
            return image;
        });
        allowed(accepted.sourceId(), accepted.snapshotId(), accepted.entryId(), accepted.variantId());
        // A stable selection identity serializes first proof. A stable blob identity also deduplicates explicit selections across owners.
        return cache.withIdentityLock(accepted.id(), () -> {
            Image frozen = owned(owner, accepted.id());
            authorize(owner, frozen.id(), false);
            byte[] firstDownload = null;
            if (frozen.blobSha() == null) {
                var known = repository.knownProof(frozen);
                ReferenceCatalogGitHubClient.GitFile proof;
                if (known.isPresent())
                    proof = new ReferenceCatalogGitHubClient.GitFile(known.get().blobSha(), known.get().byteSize());
                else {
                    var selected = allowed(frozen.sourceId(), frozen.snapshotId(), frozen.entryId(),
                            frozen.variantId());
                    if (selected.source().sourceType() == SourceType.OMILIC || selected.snapshot().commit() == null
                            || selected.entry().collection().equals("custom")) {
                        // Live JSON fixes the selected URL. Freeze validated observed bytes on
                        // first acquisition; no additional GitHub directory walk is required.
                        firstDownload = downloadSource(selected, frozen.path());
                        image(firstDownload, hash(firstDownload), firstDownload.length, "image/png");
                        proof = new ReferenceCatalogGitHubClient.GitFile(gitBlobSha(firstDownload),
                                firstDownload.length);
                    } else
                        proof = github.inspectFile(selected.source(), selected.snapshot().commit(), frozen.path());
                }
                repository.proof(owner, frozen.id(), proof.blobSha(), proof.byteSize());
                frozen = owned(owner, frozen.id());
            }
            Image fixed = frozen;
            UUID blobLock = UUID.nameUUIDFromBytes(
                    ("agenvas:private-reference-blob:v1:" + fixed.blobSha()).getBytes(StandardCharsets.UTF_8));
            byte[] first = firstDownload;
            return cache.withIdentityLock(blobLock, () -> acquireFixed(owner, fixed, first));
        });
    }
    private PrivateImage acquireFixed(UUID owner, Image fixed, byte[] firstDownload) {
        authorize(owner, fixed.id(), false);
        Image known = fixed.sha256() != null
                ? fixed
                : repository.verifiedBlob(fixed.blobSha(), fixed.byteSize()).orElse(null);
        byte[] observed = firstDownload != null ? firstDownload : known == null ? downloadVerified(owner, fixed) : null;
        if (observed != null && known != null && !hash(observed).equals(known.sha256()))
            throw invalid();
        String sha = observed != null ? hash(observed) : known.sha256();
        int[] dimensions = observed != null ? dimensions(observed) : new int[]{known.width(), known.height()};
        byte[] first = observed;
        return cache.getAndPublish(sha, fixed.byteSize(), "image/png", true, () -> authorize(owner, fixed.id(), false),
                () -> first == null ? downloadVerified(owner, fixed) : first,
                bytes -> {
                    Image published = tx.execute(ignored -> repository.publish(owner, fixed.id(), sha, dimensions[0],
                            dimensions[1], clock.instant()));
                    return view(published);
                });
    }
    private byte[] downloadVerified(UUID owner, Image image) {
        authorize(owner, image.id(), false);
        var selected = allowed(image.sourceId(), image.snapshotId(), image.entryId(), image.variantId());
        byte[] bytes = downloadSource(selected, image.path());
        if (bytes.length != image.byteSize() || !gitBlobSha(bytes).equals(image.blobSha()))
            throw invalid();
        image(bytes, hash(bytes), bytes.length, "image/png");
        authorize(owner, image.id(), false);
        return bytes;
    }
    private byte[] downloadSource(ReferenceCatalogService.Selection selected, String path) {
        if (selected.source().sourceType() == SourceType.OMILIC)
            return site.image(path);
        if (selected.snapshot().commit() == null)
            return github.fileAtRef(selected.source(), selected.source().ref(), path, MAX_IMAGE_BYTES);
        return github.file(selected.source(), selected.snapshot().commit(), path, MAX_IMAGE_BYTES);
    }
    public List<PrivateImage> list(UUID owner, String source, UUID snapshot) {
        if (source != null)
            slug(source);
        return repository.list(owner, source, snapshot).stream().map(this::view).toList();
    }
    public Image requireOwned(UUID owner, UUID id) {
        Image image = owned(owner, id);
        authorize(owner, id, true);
        return image;
    }
    public byte[] read(UUID owner, UUID id) {
        Image image = requireOwned(owner, id);
        return cache.get(image.sha256(), image.byteSize(), "image/png", false, () -> authorize(owner, id, true), () -> {
            throw new IllegalStateException("Private preview never fetches");
        });
    }
    public PrivateImage clear(UUID owner, UUID id) {
        writable();
        owned(owner, id);
        return cache.withIdentityLock(id, () -> {
            Image image = owned(owner, id);
            if (image.sha256() == null)
                throw ReferenceCatalogService.missing();
            repository.disable(owner, id, clock.instant());
            cache.clearProtecting(Set.of(image.sha256()), repository::protectedHashes);
            return view(owned(owner, id));
        });
    }
    public String title(UUID owner, UUID id) {
        Image image = requireOwned(owner, id);
        return allowed(image.sourceId(), image.snapshotId(), image.entryId(), image.variantId()).entry().title();
    }
    private Image owned(UUID owner, UUID id) {
        if (id == null)
            throw invalid();
        return repository.owned(owner, id).orElseThrow(ReferenceCatalogService::missing);
    }
    private void authorize(UUID owner, UUID id, boolean mustBeEnabled) {
        Image image = owned(owner, id);
        allowed(image.sourceId(), image.snapshotId(), image.entryId(), image.variantId());
        if (mustBeEnabled && (!image.enabled() || image.sha256() == null))
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "REFERENCE_CATALOG_CACHE_MISS",
                    "api.reference-catalog.cacheMiss");
    }
    private ReferenceCatalogService.Selection allowed(String source, UUID snapshot, String entry, String variant) {
        var selected = catalog.metadataSelection(source, snapshot, entry, variant);
        String reason = ReferenceCatalogPrivatePolicy.disabledReason(selected.source(), selected.entry());
        if (reason != null)
            throw problem(HttpStatus.CONFLICT, reason, "api.reference-catalog.privateUseUnavailable");
        return selected;
    }
    private PrivateImage view(Image image) {
        return new PrivateImage(image.id(), image.sourceId(), image.snapshotId(), image.entryId(), image.variantId(),
                image.sha256(), image.byteSize(), "image/png", image.width(), image.height(),
                "/api/v1/reference-catalog/private-images/" + image.id() + "/preview",
                image.enabled() && cache.contains(image.sha256(), image.byteSize()));
    }
    public static String gitBlobSha(byte[] bytes) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static int[] dimensions(byte[] bytes) {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext())
                throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(stream);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (java.io.IOException failure) {
            throw invalid();
        }
    }
    private void writable() {
        if (recovery)
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "RECOVERY_MODE_READ_ONLY",
                    "api.recovery-mode-write-guard.restore-verification-mode");
    }
}
