package dev.agenvas.skill.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.PrivateMediaArchive;
import dev.agenvas.asset.application.SkillAssetArchiveService;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.skill.domain.SkillContent;
import dev.agenvas.skill.infrastructure.SkillRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Account-owned authoring and local publication. Execution remains exclusively in the Agent Runtime. */
@Service
public final class SkillService {
    public static final int PAGE_SIZE = 40;
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String EMPTY_BODY = "---\nname: untitled-skill\ndescription: Describe when this Skill should be used.\n---\n\n# Instructions\n";
    private final SkillRepository repository;
    private final SkillFormat format;
    private final ObjectProvider<LibraryService> library;
    private final SkillAssetArchiveService archive;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate reads;
    public SkillService(SkillRepository repository, SkillFormat format, ObjectProvider<LibraryService> library,
            SkillAssetArchiveService archive, ObjectMapper mapper, Clock clock, PlatformTransactionManager manager) {
        this.repository = repository; this.format = format; this.library = library; this.archive = archive;
        this.mapper = mapper; this.clock = clock; tx = new TransactionTemplate(manager);
        reads = new TransactionTemplate(manager); reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public record SkillResponse(UUID id, String title, String description, boolean trashed, UUID currentVersionId,
            long version, Instant createdAt, Instant updatedAt) {}
    public record Page(List<SkillResponse> items, String nextCursor, int total) {}
    public record DraftResponse(UUID skillId, long version, String skillMd, List<Artifact.Kind> outputKinds,
            List<SkillContent.InputSlot> inputSlots, List<SkillContent.Resource> resources, List<SkillContent.DraftAsset> assets) {}
    public record VersionSummary(UUID id, UUID skillId, long versionNumber, String name, String description,
            String bundleHash, Instant createdAt) {}
    public record AssetResponse(String alias, Artifact.Kind kind, String title, String contentHash,
            SkillContent.Usage usage, boolean required, String purpose, String contentUrl, String thumbnailUrl) {}
    public record VersionResponse(UUID id, UUID skillId, long versionNumber, String bundleHash, String name,
            String description, String skillMd, List<Artifact.Kind> outputKinds, List<SkillContent.InputSlot> inputSlots,
            List<SkillContent.PublishedResource> resources, List<AssetResponse> assets, Instant createdAt) {}
    public record OperationResponse(UUID id, UUID skillId, SkillContent.OperationStatus status, UUID resultVersionId,
            String errorCode, String errorDetail) {}

    public Page list(UUID owner, String query, boolean trash, String cursor) {
        format.text(query == null ? "" : query, SkillFormat.MAX_TITLE_LENGTH, false);
        SkillRepository.Cursor parsed = parseCursor(cursor);
        return reads.execute(status -> {
            var rows = repository.list(owner, query, trash, parsed, PAGE_SIZE + 1);
            var items = rows.stream().limit(PAGE_SIZE).toList();
            String next = rows.size() > PAGE_SIZE ? cursor(items.getLast()) : null;
            return new Page(items.stream().map(this::response).toList(), next, repository.count(owner, query, trash));
        });
    }
    public SkillResponse create(UUID owner, String title, String description) {
        format.text(title, SkillFormat.MAX_TITLE_LENGTH, true);
        format.text(description, SkillFormat.MAX_DESCRIPTION_LENGTH, false);
        return tx.execute(status -> {
            Instant now = clock.instant();
            var value = new SkillContent.Catalogue(UUID.randomUUID(), owner, title.strip(), description, null, null, 0, now, now);
            repository.create(value, new SkillContent.DraftContent(SkillContent.SCHEMA_VERSION, EMPTY_BODY,
                    List.of(Artifact.Kind.IMAGE), List.of(), List.of(), List.of()));
            return response(value);
        });
    }
    public SkillResponse get(UUID owner, UUID skillId) { return response(require(owner, skillId, false)); }
    public SkillResponse updateMetadata(UUID owner, UUID skillId, long expected, String title, String description, boolean trash) {
        format.text(title, SkillFormat.MAX_TITLE_LENGTH, true);
        format.text(description, SkillFormat.MAX_DESCRIPTION_LENGTH, false);
        return tx.execute(status -> {
            var old = require(owner, skillId, true);
            if (old.version() != expected) throw conflict();
            Instant now = clock.instant();
            Instant trashedAt = trash ? old.trashedAt() == null ? now : old.trashedAt() : null;
            if (!repository.metadata(owner, skillId, expected, title.strip(), description, trashedAt, now)) throw conflict();
            return response(require(owner, skillId, false));
        });
    }
    public DraftResponse getDraft(UUID owner, UUID skillId) {
        return reads.execute(status -> { require(owner, skillId, false); return response(draft(owner, skillId, false)); });
    }
    public DraftResponse saveDraft(UUID owner, UUID skillId, long expected, SkillContent.DraftContent content) {
        SkillContent.DraftContent checked = format.validateDraft(content);
        return tx.execute(status -> {
            active(require(owner, skillId, true));
            var old = draft(owner, skillId, true);
            if (old.version() != expected) throw conflict();
            var fixed = fixSources(owner, checked);
            if (!repository.saveDraft(owner, skillId, expected, fixed, clock.instant())) throw conflict();
            return response(draft(owner, skillId, false));
        });
    }
    public List<VersionSummary> versions(UUID owner, UUID skillId) {
        return reads.execute(status -> {
            require(owner, skillId, false);
            return repository.versions(owner, skillId).stream().map(this::summary).toList();
        });
    }
    public VersionResponse getVersion(UUID owner, UUID skillId, UUID versionId) { return response(getBundle(owner, skillId, versionId)); }
    /** Runtime recovery and historical sources may read an already fixed version after trashing its catalogue. */
    public SkillContent.Version getBundle(UUID owner, UUID skillId, UUID versionId) {
        return repository.version(owner, skillId, versionId).orElseThrow(this::notFound);
    }
    public SkillContent.Version getBundleByVersion(UUID owner, UUID versionId) {
        return repository.version(owner, versionId).orElseThrow(this::notFound);
    }
    public SkillContent.Version requireSelectableVersion(UUID owner, UUID skillId, UUID versionId) {
        return reads.execute(status -> { active(require(owner, skillId, false)); return getBundle(owner, skillId, versionId); });
    }
    public SkillResponse copy(UUID owner, UUID skillId, UUID versionId, String title) {
        format.text(title, SkillFormat.MAX_TITLE_LENGTH, true);
        return tx.execute(status -> {
            var source = getBundle(owner, skillId, versionId);
            Instant now = clock.instant();
            var copied = new SkillContent.Catalogue(UUID.randomUUID(), owner, title.strip(), source.bundle().description(),
                    null, null, 0, now, now);
            repository.create(copied, copiedDraft(source));
            return response(copied);
        });
    }
    public DraftResponse copyVersionToDraft(UUID owner, UUID skillId, UUID versionId, long expectedDraftVersion) {
        return tx.execute(status -> {
            active(require(owner, skillId, true));
            var source = getBundle(owner, skillId, versionId);
            var old = draft(owner, skillId, true);
            if (old.version() != expectedDraftVersion || !repository.saveDraft(owner, skillId, expectedDraftVersion,
                    copiedDraft(source), clock.instant())) throw conflict();
            return response(draft(owner, skillId, false));
        });
    }
    public OperationResponse publish(UUID owner, UUID skillId, long expectedDraftVersion, String key) {
        format.text(key, SkillFormat.MAX_KEY_LENGTH, true);
        String hash = Sha256.hex(mapper.writeValueAsString(List.of(skillId, expectedDraftVersion)));
        return tx.execute(status -> {
            var replay = repository.key(owner, key);
            if (replay.isPresent()) return replay(replay.get(), hash);
            active(require(owner, skillId, true));
            var current = draft(owner, skillId, true);
            if (current.version() != expectedDraftVersion) throw conflict();
            format.validateDraft(current.content());
            format.publishedFrontmatter(current.content().skillMd());
            Instant now = clock.instant();
            var operation = new SkillContent.PublishOperation(UUID.randomUUID(), owner, skillId, key, hash,
                    new SkillContent.PublishInput(SkillContent.SCHEMA_VERSION, expectedDraftVersion, current.content()),
                    mapper.createObjectNode(), SkillContent.OperationStatus.ACCEPTED, 0, null, null, null, null, false, now, now);
            if (!repository.reserve(operation)) return replay(repository.key(owner, key).orElseThrow(), hash);
            return response(operation);
        });
    }
    public OperationResponse getOperation(UUID owner, UUID operationId) { return response(requireOperation(owner, operationId)); }
    public OperationResponse retry(UUID owner, UUID operationId) {
        return tx.execute(status -> {
            var value = requireOperation(owner, operationId);
            active(require(owner, value.skillId(), true));
            if (value.status() == SkillContent.OperationStatus.FAILED && !repository.retry(owner, operationId, clock.instant())) throw conflict();
            return response(requireOperation(owner, operationId));
        });
    }
    /** Trusted Agent application service performs project ownership and Agent CAS in its outer event transaction. */
    public Optional<SkillContent.Binding> getBinding(UUID owner, UUID project, UUID agent) { return repository.binding(owner, project, agent); }
    public void saveBinding(UUID owner, UUID project, UUID agent, UUID skill, UUID version) {
        if ((skill == null) != (version == null)) throw conflict();
        tx.executeWithoutResult(status -> {
            if (skill != null) { active(require(owner, skill, true)); getBundle(owner, skill, version); }
            repository.saveBinding(owner, project, agent, skill, version, clock.instant());
        });
    }
    public LibraryService.MediaFile file(UUID owner, UUID skillId, UUID versionId, String alias, boolean thumbnail) {
        var value = getBundle(owner, skillId, versionId);
        var asset = value.bundle().assets().stream().filter(item -> item.alias().equals(alias)).findFirst().orElseThrow(this::notFound);
        return archive.file(owner, asset.media(), thumbnail);
    }

    /** Claims are short transactions; decoder/file work is outside all database transactions. */
    public boolean processNext() {
        var claimed = tx.execute(status -> repository.claim(clock.instant(), clock.instant().plus(LEASE)));
        if (claimed == null || claimed.isEmpty()) return false;
        var operation = claimed.get();
        var progress = (ObjectNode) operation.progress().deepCopy();
        try {
            List<SkillContent.PublishedAsset> assets = new ArrayList<>();
            for (var binding : operation.input().draft().assets()) {
                PrivateMediaArchive.Media media;
                String title;
                if (binding.sourceVersionId() != null) {
                    var source = sourceAsset(operation.ownerId(), binding);
                    media = source.media(); title = source.title();
                } else {
                    LibraryService.PinnedSkillAsset pinned;
                    var pins = progress.withObject("pins");
                    if (pins.has(binding.alias())) pinned = mapper.treeToValue(pins.get(binding.alias()), LibraryService.PinnedSkillAsset.class);
                    else {
                        pinned = tx.execute(status -> {
                            // Fence and lock the operation before creating its temporary hard link.
                            // A stale publisher must not recreate a pin after successful cleanup.
                            saveProgress(operation, progress);
                            var value = library.getObject().pinForSkill(operation.ownerId(), binding.libraryEntryId(), binding.expectedLibraryVersion(),
                                    pinId(operation.id(), binding.alias()));
                            if (!value.contentHash().equals(binding.contentHash())) throw referenceChanged();
                            pins.set(binding.alias(), mapper.valueToTree(value));
                            saveProgress(operation, progress);
                            return value;
                        });
                    }
                    if (pinned == null) throw new IllegalStateException("Skill pin transaction returned no result");
                    title = pinned.title();
                    var archived = progress.withObject("archived");
                    if (archived.has(binding.alias())) media = mapper.treeToValue(archived.get(binding.alias()), PrivateMediaArchive.Media.class);
                    else {
                        media = archive.archivePinned(operation.ownerId(), fileId(operation.id(), binding.alias()), pinned);
                        archived.set(binding.alias(), mapper.valueToTree(media));
                        tx.executeWithoutResult(status -> saveProgress(operation, progress));
                    }
                }
                if (!media.sha256().equals(binding.contentHash())) throw referenceChanged();
                assets.add(new SkillContent.PublishedAsset(binding.alias(), Artifact.Kind.IMAGE, title, media.sha256(),
                        binding.usage(), binding.required(), binding.purpose(), media));
            }
            var draft = operation.input().draft();
            var frontmatter = format.publishedFrontmatter(draft.skillMd());
            var resources = draft.resources().stream().map(value -> new SkillContent.PublishedResource(value.path(), value.content(), Sha256.hex(value.content()))).toList();
            var bundle = new SkillContent.Bundle(SkillContent.SCHEMA_VERSION, frontmatter.name(), frontmatter.description(), draft.skillMd(),
                    draft.outputKinds(), draft.inputSlots(), resources, List.copyOf(assets));
            String hash = bundleHash(bundle);
            tx.executeWithoutResult(status -> {
                var skill = require(operation.ownerId(), operation.skillId(), true);
                active(skill);
                Instant now = clock.instant();
                var version = new SkillContent.Version(operation.id(), operation.ownerId(), operation.skillId(),
                        repository.nextVersion(operation.ownerId(), operation.skillId()), hash, bundle, now);
                repository.publish(version, skill.version());
                if (!repository.finish(operation, SkillContent.OperationStatus.SUCCEEDED, version.id(), null, null, now)) throw new LeaseLost();
            });
        } catch (RuntimeException failure) {
            String code = failure instanceof ApiProblemException problem ? problem.code() : "SKILL_ARCHIVE_FAILED";
            String detail = failure instanceof ApiProblemException problem ? mapper.writeValueAsString(problem.detail())
                    : mapper.writeValueAsString(ApiMessage.of("api.skill.archive-failed"));
            tx.executeWithoutResult(status -> repository.finish(operation, SkillContent.OperationStatus.FAILED, null, code, detail, clock.instant()));
        }
        return true;
    }
    /** The successful command is itself a durable cleanup record for deterministic temporary links. */
    public boolean cleanupNext() {
        var pending = repository.cleanup();
        if (pending.isEmpty()) return false;
        var operation = pending.get();
        for (var asset : operation.input().draft().assets()) if (asset.libraryEntryId() != null)
            archive.discardPin(operation.ownerId(), operation.ownerId() + "/" + pinId(operation.id(), asset.alias()) + ".pin");
        tx.executeWithoutResult(status -> repository.cleaned(operation.ownerId(), operation.id()));
        return true;
    }
    private void saveProgress(SkillContent.PublishOperation operation, ObjectNode progress) {
        Instant now = clock.instant();
        if (!repository.progress(operation, progress, now.plus(LEASE), now)) throw new LeaseLost();
    }
    private SkillContent.DraftContent fixSources(UUID owner, SkillContent.DraftContent content) {
        var fixed = content.assets().stream().map(binding -> {
            String hash;
            if (binding.libraryEntryId() != null) hash = library.getObject().skillAssetSource(owner, binding.libraryEntryId(), binding.expectedLibraryVersion()).contentHash();
            else hash = sourceAsset(owner, binding).contentHash();
            if (binding.contentHash() != null && !binding.contentHash().equals(hash)) throw referenceChanged();
            return new SkillContent.DraftAsset(binding.alias(), binding.libraryEntryId(), binding.expectedLibraryVersion(), binding.sourceVersionId(),
                    binding.sourceAlias(), hash, binding.usage(), binding.required(), binding.purpose());
        }).toList();
        return new SkillContent.DraftContent(SkillContent.SCHEMA_VERSION, content.skillMd(), content.outputKinds(), content.inputSlots(), content.resources(), fixed);
    }
    private SkillContent.PublishedAsset sourceAsset(UUID owner, SkillContent.DraftAsset binding) {
        var source = repository.version(owner, binding.sourceVersionId()).orElseThrow(this::notFound);
        return source.bundle().assets().stream().filter(value -> value.alias().equals(binding.sourceAlias())).findFirst().orElseThrow(this::notFound);
    }
    private SkillContent.DraftContent copiedDraft(SkillContent.Version source) {
        var bundle = source.bundle();
        var assets = bundle.assets().stream().map(asset -> new SkillContent.DraftAsset(asset.alias(), null, null, source.id(), asset.alias(),
                asset.contentHash(), asset.usage(), asset.required(), asset.purpose())).toList();
        return new SkillContent.DraftContent(SkillContent.SCHEMA_VERSION, bundle.skillMd(), bundle.outputKinds(), bundle.inputSlots(),
                bundle.resources().stream().map(resource -> new SkillContent.Resource(resource.path(), resource.content())).toList(), assets);
    }
    private String bundleHash(SkillContent.Bundle bundle) {
        var safeAssets = bundle.assets().stream().map(asset -> List.of(asset.alias(), asset.kind(), asset.title(), asset.contentHash(),
                asset.usage(), asset.required(), asset.purpose())).toList();
        return Sha256.hex(mapper.writeValueAsString(List.of(bundle.schemaVersion(), bundle.name(), bundle.description(), bundle.skillMd(),
                bundle.outputKinds(), bundle.inputSlots(), bundle.resources(), safeAssets)));
    }
    private UUID pinId(UUID operation, String alias) { return UUID.nameUUIDFromBytes(("agenvas:skill-pin:v1:" + operation + ":" + alias).getBytes(StandardCharsets.UTF_8)); }
    private UUID fileId(UUID operation, String alias) { return UUID.nameUUIDFromBytes(("agenvas:skill-file:v1:" + operation + ":" + alias).getBytes(StandardCharsets.UTF_8)); }
    private SkillContent.Catalogue require(UUID owner, UUID skill, boolean lock) { return repository.skill(owner, skill, lock).orElseThrow(this::notFound); }
    private SkillContent.Draft draft(UUID owner, UUID skill, boolean lock) { return repository.draft(owner, skill, lock).orElseThrow(this::notFound); }
    private SkillContent.PublishOperation requireOperation(UUID owner, UUID id) { return repository.operation(owner, id).orElseThrow(this::notFound); }
    private void active(SkillContent.Catalogue value) { if (value.trashedAt() != null) throw problem(HttpStatus.CONFLICT, "SKILL_TRASHED", "api.skill.trashed"); }
    private OperationResponse replay(SkillContent.PublishOperation value, String hash) {
        if (!value.payloadHash().equals(hash)) throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "api.skill.key-conflict");
        return response(value);
    }
    private SkillResponse response(SkillContent.Catalogue value) { return new SkillResponse(value.id(), value.title(), value.description(),
            value.trashedAt() != null, value.currentVersionId(), value.version(), value.createdAt(), value.updatedAt()); }
    private DraftResponse response(SkillContent.Draft value) { var content = value.content(); return new DraftResponse(value.skillId(), value.version(),
            content.skillMd(), content.outputKinds(), content.inputSlots(), content.resources(), content.assets()); }
    private VersionSummary summary(SkillContent.Version value) { return new VersionSummary(value.id(), value.skillId(), value.versionNumber(),
            value.bundle().name(), value.bundle().description(), value.bundleHash(), value.createdAt()); }
    private VersionResponse response(SkillContent.Version value) {
        var bundle = value.bundle();
        var assets = bundle.assets().stream().map(asset -> {
            String path = "/api/v1/skills/" + value.skillId() + "/versions/" + value.id() + "/assets/" + asset.alias();
            return new AssetResponse(asset.alias(), asset.kind(), asset.title(), asset.contentHash(), asset.usage(), asset.required(), asset.purpose(), path + "/file", path + "/thumbnail");
        }).toList();
        return new VersionResponse(value.id(), value.skillId(), value.versionNumber(), value.bundleHash(), bundle.name(), bundle.description(),
                bundle.skillMd(), bundle.outputKinds(), bundle.inputSlots(), bundle.resources(), assets, value.createdAt());
    }
    private OperationResponse response(SkillContent.PublishOperation value) { return new OperationResponse(value.id(), value.skillId(), value.status(),
            value.resultVersionId(), value.errorCode(), value.errorDetail()); }
    private SkillRepository.Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] values = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (values.length != 2) throw new IllegalArgumentException();
            return new SkillRepository.Cursor(Instant.parse(values[0]), UUID.fromString(values[1]));
        } catch (RuntimeException invalid) { throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "SKILL_INVALID_CURSOR", "api.skill.invalid-content"); }
    }
    private String cursor(SkillContent.Catalogue value) { return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((value.updatedAt() + "|" + value.id()).getBytes(StandardCharsets.UTF_8)); }
    private ApiProblemException notFound() { return problem(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "api.skill.not-found"); }
    private ApiProblemException conflict() { return problem(HttpStatus.CONFLICT, "SKILL_VERSION_CONFLICT", "api.skill.conflict"); }
    private ApiProblemException referenceChanged() { return problem(HttpStatus.CONFLICT, "SKILL_REFERENCE_CHANGED", "api.skill.reference-changed"); }
    private ApiProblemException problem(HttpStatus status, String code, String key) { return new ApiProblemException(status, code,
            ApiMessage.of("api.skill.operation-failed"), ApiMessage.of(key), status == HttpStatus.CONFLICT); }
    private static final class LeaseLost extends RuntimeException {}
}
