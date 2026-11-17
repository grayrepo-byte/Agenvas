package dev.agenvas.referencecatalog.application;

import static dev.agenvas.referencecatalog.application.ReferenceCatalogValidation.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import dev.agenvas.referencecatalog.infrastructure.*;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Manual network synchronization publishes only complete immutable metadata snapshots. */
@Service
public class ReferenceCatalogService {
    private final ReferenceCatalogRepository repository;
    private final ReferenceCatalogGitHubClient github;
    private final ReferenceCatalogParser parser;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ReferenceCatalogSiteClient site;
    private final ReferenceCatalogImageCache cache;
    private final boolean recovery;
    private final CustomCostumeRepository costumes;
    public ReferenceCatalogService(ReferenceCatalogRepository repository, ReferenceCatalogGitHubClient github,
            ReferenceCatalogSiteClient site, ReferenceCatalogParser parser, Clock clock,
            PlatformTransactionManager transactions, ReferenceCatalogImageCache cache,
            @org.springframework.beans.factory.annotation.Value("${agenvas.recovery-mode:false}") boolean recovery,
            CustomCostumeRepository costumes) {
        this.costumes = costumes;
        this.repository = repository;
        this.github = github;
        this.site = site;
        this.parser = parser;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.cache = cache;
        this.recovery = recovery;
    }
    public List<Source> sources() {
        return repository.sources();
    }
    public Source source(String id) {
        return repository.source(id).orElseThrow(ReferenceCatalogService::missing);
    }
    public Source create(String id, String name, String owner, String repo, String ref, String manifestPath,
            Format format, boolean approved, SourceType sourceType) {
        SourceType type = sourceType == null ? SourceType.GITHUB : sourceType;
        slug(id);
        slug(owner);
        slug(repo);
        ref(ref);
        if (name == null || name.isBlank() || name.length() > 160 || format == null)
            throw invalid();
        if (type == SourceType.OMILIC && format != Format.OPEN_POSE_COSTUME)
            throw invalid();
        String file = manifestPath == null || manifestPath.isBlank()
                ? type == SourceType.OMILIC
                        ? "data/catalog.json"
                        : format == Format.OPEN_POSE_COSTUME
                                ? "public/data/catalog.json"
                                : "agenvas-reference-pack.json"
                : path(manifestPath);
        if (owner.equalsIgnoreCase("hongye007") && repo.equalsIgnoreCase("open-pose-costume")
                && format != Format.OPEN_POSE_COSTUME)
            throw invalid();
        Source s = new Source(id, name, owner, repo, ref, file, format, true, approved, 0, null, "NEVER", null, null,
                null, type);
        if (!repository.insert(s, clock.instant()))
            throw conflict();
        return source(id);
    }
    public Source update(String id, long expected, boolean enabled, boolean approved) {
        source(id);
        if (expected < 0 || !repository.update(id, expected, enabled, approved))
            throw conflict();
        return source(id);
    }
    public record SyncResult(String state, Source source, UUID snapshotId, int entryCount, int variantCount) {
    }
    public SyncResult sync(String id, long expected) {
        Source selected = source(id);
        if (selected.version() != expected)
            throw conflict();
        if (!selected.enabled())
            return new SyncResult("DISABLED", selected, selected.currentSnapshotId(), 0, 0);
        UUID token = UUID.randomUUID();
        if (!repository.claim(id, expected, token, clock.instant())) {
            if (source(id).version() != expected)
                throw conflict();
            return new SyncResult("RUNNING", source(id), selected.currentSnapshotId(), 0, 0);
        }
        try {
            String commit;
            byte[] bytes;
            if (selected.sourceType() == SourceType.OMILIC) {
                commit = null;
                bytes = site.catalog(selected.manifestPath());
            } else if (selected.format() == Format.OPEN_POSE_COSTUME && !selected.ref().matches("[a-fA-F0-9]{40}")) {
                commit = null;
                bytes = github.fileAtRef(selected, selected.ref(), selected.manifestPath(),
                        ReferenceCatalogParser.MAX_METADATA_BYTES);
            } else {
                commit = github.resolve(selected);
                bytes = github.file(selected, commit, selected.manifestPath(),
                        ReferenceCatalogParser.MAX_METADATA_BYTES);
            }
            var previous = selected.currentSnapshotId() == null
                    ? Optional.<Snapshot>empty()
                    : repository.snapshot(id, selected.currentSnapshotId());
            // Identical JSON retains its snapshot identity and every image indexed by it.
            Snapshot parsed = parser.parse(selected, commit, bytes);
            Snapshot snapshot = previous.filter(s -> s.manifestHash().equals(parsed.manifestHash())
                    && s.entries().equals(parsed.entries()) && java.util.Objects.equals(s.commit(), parsed.commit()))
                    .orElse(parsed);
            boolean published = Boolean.TRUE
                    .equals(tx.execute(ignored -> repository.publish(id, expected, token, snapshot, clock.instant())));
            return new SyncResult(published ? "SUCCESS" : "SUPERSEDED", source(id),
                    published ? snapshot.id() : source(id).currentSnapshotId(),
                    published ? snapshot.entries().size() : 0,
                    published ? snapshot.entries().stream().mapToInt(e -> e.variants().size()).sum() : 0);
        } catch (ApiProblemException failure) {
            repository.fail(id, token, failure.code());
            return new SyncResult("FAILED", source(id), selected.currentSnapshotId(), 0, 0);
        } catch (RuntimeException failure) {
            repository.fail(id, token, "REFERENCE_CATALOG_SYNC_FAILED");
            throw failure;
        }
    }
    public record Filters(List<String> categories, List<String> tags, List<String> styles, List<String> collections,
            List<String> angles) {
    }
    public record VariantView(String id, String label, String view, String angle, Map<String, String> localizedLabel,
            String externalUrl,
            String previewUrl, String sha256, Long byteSize, String contentType, String availability,
            boolean importAllowed, String disabledReason, boolean cached, boolean privateCacheAllowed,
            String privateCacheDisabledReason) {
    }
    public record EntryView(String sourceId, UUID snapshotId, String id, Kind kind, String title,
            Map<String, String> localizedTitle, String code,
            String category, Map<String, String> localizedCategory, Map<String, String> series, List<String> tags,
            Map<String, Map<String, String>> localizedTags, Map<String, String> localizedKeywords,
            Map<String, String> facets, String collection, boolean extended, String externalUrl, boolean importAllowed,
            String disabledReason, List<VariantView> variants, Origin origin, long version) {
    }
    public record Page(List<EntryView> items, long total, int offset, int limit, Filters filters) {
    }
    public Page list(Kind kind, String sourceId, String query, String category, String tag, String style,
            String collection, Boolean extended, String angle, int offset, int limit) {
        return list(kind, sourceId, query, category, tag, style, collection, extended, angle, offset, limit, false);
    }
    /** An automatic website default can include independent costumes without reading other external sources. */
    public Page list(Kind kind, String sourceId, String query, String category, String tag, String style,
            String collection, Boolean extended, String angle, int offset, int limit, boolean includeSelfCreated) {
        if (offset < 0 || limit < 1 || limit > 100 || query == null || query.length() > 200)
            throw invalid();
        if (sourceId != null)
            source(sourceId);
        List<EntryView> all = new ArrayList<>();
        for (Source source : sources()) {
            if (sourceId != null && !source.id().equals(sourceId) || source.currentSnapshotId() == null)
                continue;
            Snapshot snapshot = repository.snapshot(source.id(), source.currentSnapshotId())
                    .orElseThrow(ReferenceCatalogService::missing);
            for (Entry entry : snapshot.entries())
                if (kind == null || entry.kind() == kind)
                    all.add(view(source, snapshot, entry));
        }
        if ((sourceId == null || includeSelfCreated) && (kind == null || kind == Kind.COSTUME))
            all.addAll(costumes.list().stream().map(CustomCostumeService::view).toList());
        Filters filters = new Filters(distinct(all.stream().map(EntryView::category).toList()),
                distinct(all.stream().flatMap(e -> e.tags().stream()).toList()),
                distinct(all.stream().map(e -> e.facets().getOrDefault("style", "")).toList()),
                distinct(all.stream().map(EntryView::collection).toList()),
                distinct(all.stream().flatMap(e -> e.variants().stream().map(VariantView::angle)).toList()));
        List<String> needles = java.util.Arrays.stream(normalize(query).strip().split("\\s+")).filter(s -> !s.isBlank())
                .toList();
        List<EntryView> matching = all.stream()
                .filter(e -> (category == null || category.equals(e.category()))
                        && (tag == null || e.tags().contains(tag))
                        && (style == null || style.equals(e.facets().get("style")))
                        && (collection == null || collection.equals(e.collection()))
                        && (extended == null || extended == e.extended())
                        && (angle == null || e.variants().stream().anyMatch(v -> angle.equals(v.angle())))
                        && needles.stream().allMatch(needle -> searchable(e).contains(needle)))
                .toList();
        return new Page(matching.stream().skip(offset).limit(limit).toList(), matching.size(), offset, limit, filters);
    }
    private static String searchable(EntryView e) {
        return normalize(e.id() + " " + e.code() + " " + e.title() + " " + e.localizedTitle().values() + " "
                + e.localizedKeywords().values() + " " + e.localizedCategory().values() + " " + e.series().values()
                + " " + e.tags() + " " + e.localizedTags().values());
    }
    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    private static List<String> distinct(List<String> values) {
        return values.stream().filter(s -> !s.isBlank()).distinct().sorted().toList();
    }
    private EntryView view(Source source, Snapshot snapshot, Entry e) {
        List<VariantView> variants = e.variants().stream().map(v -> {
            boolean allowed = ReferenceCatalogParser.importable(source, snapshot, v);
            return new VariantView(v.id(), v.label(), v.view(), v.angle(), v.localizedLabel(), v.externalUrl(),
                    allowed ? previewUrl(source.id(), snapshot.id(), e.id(), v.id()) : null,
                    v.sha256(), v.byteSize(), v.contentType(), allowed ? "AVAILABLE" : "LINK_ONLY", allowed,
                    allowed ? null : disabled(source), cache.contains(v.sha256(), v.byteSize()),
                    dev.agenvas.referencecatalog.domain.ReferenceCatalogPrivatePolicy.disabledReason(source, e) == null,
                    dev.agenvas.referencecatalog.domain.ReferenceCatalogPrivatePolicy.disabledReason(source, e));
        }).toList();
        boolean allowed = variants.stream().anyMatch(VariantView::importAllowed);
        return new EntryView(source.id(), snapshot.id(), e.id(), e.kind(), e.title(), e.localizedTitle(), e.code(),
                e.category(), e.localizedCategory(), e.series(), e.tags(), e.localizedTags(), e.localizedKeywords(),
                e.facets(), e.collection(), e.extended(), e.externalUrl(), allowed, allowed ? null : disabled(source),
                variants, Origin.THIRD_PARTY, 0);
    }
    public record Selection(Source source, Snapshot snapshot, Entry entry, Variant variant) {
    }
    public Selection metadataSelection(String sourceId, UUID snapshotId, String entryId, String variantId) {
        Source s = source(sourceId);
        Snapshot snapshot = repository.snapshot(sourceId, snapshotId).orElseThrow(ReferenceCatalogService::missing);
        Entry e = snapshot.entries().stream().filter(x -> x.id().equals(entryId)).findFirst()
                .orElseThrow(ReferenceCatalogService::missing);
        Variant v = e.variants().stream().filter(x -> x.id().equals(variantId)).findFirst()
                .orElseThrow(ReferenceCatalogService::missing);
        return new Selection(s, snapshot, e, v);
    }
    public Selection selection(String sourceId, UUID snapshotId, String entryId, String variantId) {
        var selected = metadataSelection(sourceId, snapshotId, entryId, variantId);
        if (!ReferenceCatalogParser.importable(selected.source(), selected.snapshot(), selected.variant()))
            throw problem(HttpStatus.CONFLICT, disabled(selected.source()), "api.reference-catalog.rightsRequired");
        return selected;
    }
    public byte[] download(Selection selected) {
        return download(selected, () -> {
        });
    }
    public byte[] download(Selection selected, Runnable stillActive) {
        Runnable authorize = () -> {
            stillActive.run();
            Source current = source(selected.source().id());
            if (!ReferenceCatalogParser.importable(current, selected.snapshot(), selected.variant()))
                throw problem(HttpStatus.CONFLICT, disabled(current), "api.reference-catalog.rightsRequired");
        };
        var v = selected.variant();
        return cache.get(v.sha256(), v.byteSize(), v.contentType(), !recovery, authorize,
                () -> github.file(selected.source(), selected.snapshot().commit(), v.path(), MAX_IMAGE_BYTES));
    }
    /** Freeze only explicitly selected metadata; callers cannot submit raw file paths or hashes. */
    public List<Selection> selections(String sourceId, UUID snapshotId, List<String> entryIds) {
        if (snapshotId == null || entryIds == null || entryIds.size() > 5000
                || new HashSet<>(entryIds).size() != entryIds.size())
            throw invalid();
        Source s = source(sourceId);
        Snapshot snapshot = repository.snapshot(sourceId, snapshotId).orElseThrow(ReferenceCatalogService::missing);
        Set<String> requested = new HashSet<>(entryIds);
        List<Selection> selections = new ArrayList<>();
        Set<String> found = new HashSet<>();
        for (Entry e : snapshot.entries())
            if (requested.isEmpty() || requested.contains(e.id())) {
                found.add(e.id());
                for (Variant v : e.variants()) {
                    if (!ReferenceCatalogParser.importable(s, snapshot, v))
                        throw problem(HttpStatus.CONFLICT, disabled(s), "api.reference-catalog.rightsRequired");
                    selections.add(new Selection(s, snapshot, e, v));
                }
            }
        if (!found.containsAll(requested) || selections.isEmpty())
            throw missing();
        return List.copyOf(selections);
    }
    public Set<String> cachedImageHashes(String sourceId, UUID snapshotId, List<String> entryIds) {
        Source source = source(sourceId);
        UUID fixed = snapshotId == null ? source.currentSnapshotId() : snapshotId;
        if (fixed == null)
            return Set.of();
        Snapshot snapshot = repository.snapshot(sourceId, fixed).orElseThrow(ReferenceCatalogService::missing);
        Set<String> requested = new HashSet<>(entryIds), found = new HashSet<>(), hashes = new HashSet<>();
        for (Entry e : snapshot.entries())
            if (requested.isEmpty() || requested.contains(e.id())) {
                found.add(e.id());
                for (Variant v : e.variants())
                    if (v.sha256() != null)
                        hashes.add(v.sha256());
            }
        if (!found.containsAll(requested))
            throw missing();
        return Set.copyOf(hashes);
    }
    public static String previewUrl(String source, UUID snapshot, String entry, String variant) {
        return "/api/v1/reference-catalog/sources/" + source + "/snapshots/" + snapshot + "/entries/" + entry
                + "/variants/" + variant + "/preview";
    }
    private static String disabled(Source source) {
        return !source.enabled()
                ? "REFERENCE_CATALOG_SOURCE_DISABLED"
                : source.format() == Format.OPEN_POSE_COSTUME
                        ? "REFERENCE_CATALOG_LINK_ONLY"
                        : "REFERENCE_CATALOG_RIGHTS_REQUIRED";
    }
    public static ApiProblemException missing() {
        return problem(HttpStatus.NOT_FOUND, "REFERENCE_CATALOG_NOT_FOUND", "api.reference-catalog.notFound");
    }
    public static ApiProblemException conflict() {
        return problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "api.reference-catalog.conflict");
    }
}
