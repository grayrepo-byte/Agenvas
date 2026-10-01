package dev.agenvas.library.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.application.PrivateMediaArchive;
import dev.agenvas.asset.application.PrivateMediaArchive.Media;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.library.domain.LibraryCommand;
import dev.agenvas.library.domain.LibraryEntry;
import dev.agenvas.library.infrastructure.LibraryRepository;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Account catalogue and durable local transfers; project writes go through application services. */
@Service
public class LibraryService {
    public static final int MAX_NAME_LENGTH = 160;
    public static final int MAX_DRAFT_PROMPT_LENGTH = 20_000;
    public static final int MAX_COMMAND_KEY_LENGTH = 200;
    private static final int PAGE_SIZE = 30;
    private static final int IMPORT_CARD_SIZE = 320;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(LibraryService.class);
    private static final java.util.Set<String> NON_RETRYABLE_CODES = java.util.Set.of("VERSION_CONFLICT", "PROVIDER_UNSUPPORTED_INPUT", "VALIDATION_ERROR", "LIBRARY_REFERENCE_INVALID", "ARTIFACT_ORIGIN_INVALID");
    private static final int SCHEMA_VERSION = 1;
    private static final Duration LEASE = Duration.ofMinutes(5);
    private final LibraryRepository repository;
    private final PrivateMediaArchive archive;
    private final AssetService assets;
    private final ArtifactService artifacts;
    private final CanvasItemQueryService items;
    private final CanvasService canvas;
    private final MediaDraftService drafts;
    private final ProjectService projects;
    private final dev.agenvas.task.application.DirectMediaTaskService mediaTasks;
    private final dev.agenvas.event.application.ProjectEventService events;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final dev.agenvas.shared.lifecycle.ShutdownGate shutdown;
    private final TransactionTemplate tx;
    private final TransactionTemplate reads;

    public LibraryService(LibraryRepository repository, PrivateMediaArchive archive, AssetService assets,
            ArtifactService artifacts, CanvasItemQueryService items, CanvasService canvas,
            MediaDraftService drafts, ProjectService projects, dev.agenvas.task.application.DirectMediaTaskService mediaTasks, dev.agenvas.event.application.ProjectEventService events, Clock clock, ObjectMapper mapper, dev.agenvas.shared.lifecycle.ShutdownGate shutdown,
            PlatformTransactionManager transactions) {
        this.repository = repository; this.archive = archive; this.assets = assets; this.artifacts = artifacts;
        this.items = items; this.canvas = canvas; this.drafts = drafts; this.projects = projects;
        this.clock = clock; this.mapper = mapper; this.mediaTasks = mediaTasks; this.events = events; this.shutdown = shutdown;
        tx = new TransactionTemplate(transactions);
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public record SourceContext(String title, Artifact.Kind kind, UUID versionId,
            long expectedSelectionEpoch, long expectedArtifactVersion, JsonNode textContent,
            UUID assetId, int versionNo) {}
    public SourceContext source(UUID owner, UUID project, UUID itemId) {
        return reads.execute(ignored -> {
            projects.requireActiveProject(owner, project);
            var item = items.requireArtifactItem(owner, project, itemId);
            var artifact = artifacts.get(owner, project, item.subjectId());
            boolean text = artifact.artifact().kind() == Artifact.Kind.TEXT;
            UUID versionId = text ? artifact.artifact().resourceDefaultVersionId() : item.selectedVersionId();
            if (versionId == null || (!text && drafts.get(owner, project, itemId).displayMode() != MediaDraft.DisplayMode.RESULT))
                throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "LIBRARY_NO_CONTENT", "请先选用一个已保存的内容结果。");
            var version = artifacts.requireVersion(owner, project, item.subjectId(), versionId);
            if (text && version.content().path("text").asText().isBlank())
                throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "LIBRARY_NO_CONTENT", "空白文字不能保存为资产。");
            int versionNo = text ? version.versionNo() : canvas.listMediaVersions(owner, project, itemId).stream()
                    .sorted(java.util.Comparator.comparingInt(dev.agenvas.artifact.domain.ArtifactVersion::versionNo))
                    .map(dev.agenvas.artifact.domain.ArtifactVersion::id).toList().indexOf(versionId) + 1;
            return new SourceContext(item.title(), artifact.artifact().kind(), versionId,
                    text ? 0 : canvas.mediaSelectionEpoch(owner, project, itemId), artifact.artifact().version(),
                    text ? version.content().deepCopy() : null,
                    text ? null : UUID.fromString(version.content().path("assetId").asText()), versionNo);
        });
    }

    public LibraryCommand save(UUID owner, UUID project, UUID itemId, UUID versionId,
            long selectionEpoch, Long artifactVersion, String name, LibraryEntry.Category category, String key) {
        name = name(name); key = key(key);
        String hash = hash(mapper.writeValueAsString(List.of("SAVE", project, itemId, versionId,
                selectionEpoch, artifactVersion == null ? -1 : artifactVersion, name, category)));
        var replay = replay(owner, key, hash);
        if (replay != null) return replay;
        SourceContext snapshot = source(owner, project, itemId);
        checkSource(snapshot, versionId, selectionEpoch, artifactVersion);
        UUID commandId = UUID.randomUUID();
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION)
                .put("name", name).put("category", category.name()).put("kind", snapshot.kind().name())
                .put("sourceVersionId", versionId.toString());
        input.set("source", mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION).put("projectId", project.toString())
                .put("canvasItemId", itemId.toString()).put("versionId", versionId.toString())
                .put("title", snapshot.title()).put("origin", "CANVAS"));
        if (snapshot.textContent() != null) input.set("textContent", snapshot.textContent());
        else {
            var file = assets.get(owner, project, snapshot.assetId());
            input.put("pin", archive.pin(owner, commandId, file.path())).put("sha256", file.asset().sha256());
        }
        try {
            final String acceptedName = name, acceptedKey = key;
            LibraryCommand result = tx.execute(ignored -> {
                return events.recordChange(owner, project, () -> {
                    checkSource(source(owner, project, itemId), versionId, selectionEpoch, artifactVersion);
                    return dev.agenvas.event.application.ProjectEventService.Change.unchanged(
                            accept(owner, commandId, acceptedKey, hash, LibraryCommand.Kind.SAVE, input));
                }).value();
            });
            if (!result.id().equals(commandId)) archive.discardPin(owner, input.path("pin").asText(null));
            return result;
        } catch (RuntimeException failure) {
            archive.discardPin(owner, input.path("pin").asText(null)); throw failure;
        }
    }

    public static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;
    public static final long MAX_IMAGE_UPLOAD_BYTES = 20L * 1024 * 1024;
    public LibraryCommand upload(UUID owner, String name, LibraryEntry.Category category,
            dev.agenvas.asset.domain.Asset.MediaKind kind, String key, org.springframework.web.multipart.MultipartFile file) {
        name = name(name); key = key(key);
        if (file.isEmpty() || file.getSize() > (kind == dev.agenvas.asset.domain.Asset.MediaKind.IMAGE ? MAX_IMAGE_UPLOAD_BYTES : MAX_UPLOAD_BYTES))
            throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "ASSET_TOO_LARGE", "文件为空或超过资产上传大小限制。");
        UUID id = UUID.randomUUID();
        Media media;
        try (var stream = file.getInputStream()) { media = archive.archive(owner, id, kind, stream); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read upload", failure); }
        String hash = hash(mapper.writeValueAsString(List.of("UPLOAD", name, category, kind, media.sha256())));
        try {
            var replay = replay(owner, key, hash);
            if (replay != null) { archive.discard(owner, media); return replay; }
            ObjectNode input = mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION).put("name", name)
                    .put("category", category.name()).put("kind", kind.name()).put("sha256", media.sha256());
            input.set("source", mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION).put("origin", "UPLOAD"));
            input.put("pin", archive.pin(owner, id, archive.file(owner, media, false)));
            LibraryCommand result = acceptPinned(owner, id, key, hash, LibraryCommand.Kind.UPLOAD, input, () -> {});
            if (!result.id().equals(id)) archive.discard(owner, media);
            return result;
        } catch (RuntimeException failure) { archive.discard(owner, media); throw failure; }
    }

    public LibraryCommand importEntry(UUID owner, UUID project, UUID entryId, long expected,
            java.math.BigDecimal x, java.math.BigDecimal y, String key) {
        key = key(key);
        String hash = hash(mapper.writeValueAsString(List.of("IMPORT", project, entryId, expected, x, y)));
        var replay = replay(owner, key, hash);
        if (replay != null) return replay;
        projects.requireActiveProject(owner, project);
        LibraryEntry entry = require(owner, entryId);
        checkEntry(entry, expected);
        UUID id = UUID.randomUUID();
        ObjectNode input = importInput(owner, id, project, entry).put("x", x).put("y", y);
        return acceptPinned(owner, id, key, hash, LibraryCommand.Kind.IMPORT, input, () -> {
            projects.requireActiveProject(owner, project); checkEntry(requireLocked(owner, entryId), expected);
        });
    }

    public record ReferenceDraft(long expectedVersion, String prompt, JsonNode parameters, Integer durationSeconds,
            UUID capabilityId, MediaDraft.VideoInputMode videoInputMode, List<MediaDraftService.SaveMediaInput> mediaInputs,
            List<MediaDraft.PromptMention> mentions) {}
    public LibraryCommand reference(UUID owner, UUID project, UUID item, UUID entryId, long expected,
            ReferenceDraft draft, MediaDraft.InputRole role, String color, String key) {
        key = key(key);
        String hash = hash(mapper.writeValueAsString(List.of("REFERENCE", project, item, entryId, expected, draft, role, color)));
        var replay = replay(owner, key, hash); if (replay != null) return replay;
        LibraryEntry entry = require(owner, entryId); checkEntry(entry, expected);
        if (entry.kind() != Artifact.Kind.IMAGE && entry.kind() != Artifact.Kind.AUDIO)
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "LIBRARY_REFERENCE_INVALID", "只有图片和音频资产可作为参考。");
        if (drafts.get(owner, project, item).version() != draft.expectedVersion()) throw conflict();
        UUID id = UUID.randomUUID();
        ObjectNode input = importInput(owner, id, project, entry).put("itemId", item.toString())
                .put("role", role.name()).put("color", color);
        input.set("draft", mapper.valueToTree(draft));
        return acceptPinned(owner, id, key, hash, LibraryCommand.Kind.REFERENCE, input, () -> {
            checkEntry(requireLocked(owner, entryId), expected);
            if (drafts.get(owner, project, item).version() != draft.expectedVersion()) throw conflict();
        });
    }

    private ObjectNode importInput(UUID owner, UUID commandId, UUID project, LibraryEntry entry) {
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION)
                .put("projectId", project.toString()).put("entryId", entry.id().toString())
                .put("name", entry.name()).put("kind", entry.kind().name());
        input.set("source", mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION).put("entryId", entry.id().toString())
                .put("name", entry.name()).put("category", entry.category().name()).put("kind", entry.kind().name()));
        if (entry.fileId() == null) input.set("textContent", entry.textContent().deepCopy());
        else {
            Media media = repository.file(owner, entry.fileId());
            input.put("pin", archive.pin(owner, commandId, archive.file(owner, media, false))).put("sha256", media.sha256());
        }
        return input;
    }

    private LibraryCommand acceptPinned(UUID owner, UUID id, String key, String hash, LibraryCommand.Kind kind,
            ObjectNode input, Runnable validate) {
        try {
            LibraryCommand result = tx.execute(ignored -> { validate.run(); return accept(owner, id, key, hash, kind, input); });
            if (!result.id().equals(id)) archive.discardPin(owner, input.path("pin").asText(null));
            return result;
        } catch (RuntimeException failure) { archive.discardPin(owner, input.path("pin").asText(null)); throw failure; }
    }
    private void checkEntry(LibraryEntry entry, long expected) {
        if (entry.version() != expected) throw problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资产已变化，请刷新后重试。");
        if (entry.trashedAt() != null) throw problem(HttpStatus.CONFLICT, "LIBRARY_ENTRY_TRASHED", "请先从回收站恢复资产。");
    }
    public record MediaFile(java.nio.file.Path path, String contentType, long size) {}
    public MediaFile file(UUID owner, UUID entryId, boolean thumbnail) {
        LibraryEntry entry = require(owner, entryId);
        if (entry.fileId() == null) throw problem(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "文字资产没有媒体文件。");
        Media media = repository.file(owner, entry.fileId());
        return new MediaFile(archive.file(owner, media, thumbnail), thumbnail ? "image/png" : media.contentType(),
                thumbnail ? media.thumbnailByteSize() : media.byteSize());
    }

    private void checkSource(SourceContext current, UUID version, long epoch, Long artifactVersion) {
        if (!current.versionId().equals(version) || (current.kind() == Artifact.Kind.TEXT
                ? artifactVersion == null || current.expectedArtifactVersion() != artifactVersion
                : current.expectedSelectionEpoch() != epoch))
            throw problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "选中的内容已变化，请刷新预览后重新保存。");
    }
    private LibraryCommand accept(UUID owner, UUID id, String key, String hash, LibraryCommand.Kind kind, JsonNode input) {
        Instant now = clock.instant();
        var command = new LibraryCommand(id, owner, key, hash, kind, input, LibraryCommand.Status.ACCEPTED,
                0, null, null, null, null, now, now);
        if (!repository.reserve(command)) return Objects.requireNonNull(replay(owner, key, hash));
        return command;
    }
    private LibraryCommand replay(UUID owner, String key, String hash) {
        var existing = repository.key(owner, key).orElse(null);
        if (existing != null && !existing.payloadHash().equals(hash))
            throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "相同命令键不能用于不同的内容。");
        return existing;
    }

    public record EntryResponse(UUID id, String name, LibraryEntry.Category category, Artifact.Kind kind,
            JsonNode textContent, String contentType, Long byteSize, Integer width, Integer height,
            Integer durationMs, boolean hasThumbnail, JsonNode source, boolean favorite,
            Instant trashedAt, long version, Instant createdAt, Instant updatedAt) {}
    public record Page(List<EntryResponse> items, String nextCursor, int total, Map<String, Integer> categoryCounts) {}
    public EntryResponse get(UUID owner, UUID id) { return reads.execute(ignored -> response(require(owner, id))); }
    public Page list(UUID owner, LibraryEntry.Category category, Artifact.Kind kind, String query, boolean favorite,
            boolean trash, LibraryEntry.Sort sort, String cursor) {
        return reads.execute(ignored -> {
            LibraryRepository.Cursor after = null;
            if (cursor != null && !cursor.isBlank()) {
                try { after = mapper.readValue(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8),
                        LibraryRepository.Cursor.class);
                    if (after == null || after.id() == null || after.value() == null || after.value().length() > MAX_NAME_LENGTH)
                        throw new IllegalArgumentException("Invalid cursor fields");
                    if (sort != LibraryEntry.Sort.NAME) Instant.parse(after.value());
                }
                catch (RuntimeException invalid) { throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "分页游标无效。"); }
            }
            var all = repository.list(owner, category, kind, query, favorite, trash, sort, after, PAGE_SIZE + 1);
            var page = all.stream().limit(PAGE_SIZE).toList();
            String next = null;
            if (all.size() > PAGE_SIZE) {
                var last = page.get(page.size() - 1);
                String value = sort == LibraryEntry.Sort.NAME ? last.name()
                        : (sort == LibraryEntry.Sort.UPDATED ? last.updatedAt() : last.createdAt()).toString();
                next = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsString(
                        new LibraryRepository.Cursor(value, last.id())).getBytes(StandardCharsets.UTF_8));
            }
            return new Page(page.stream().map(this::response).toList(), next,
                    repository.count(owner, category, kind, query, favorite, trash), repository.counts(owner, trash));
        });
    }
    private EntryResponse response(LibraryEntry entry) {
        Media media = entry.fileId() == null ? null : repository.file(entry.ownerId(), entry.fileId());
        return new EntryResponse(entry.id(), entry.name(), entry.category(), entry.kind(), entry.textContent(),
                media == null ? null : media.contentType(), media == null ? null : media.byteSize(),
                media == null ? null : media.width(), media == null ? null : media.height(),
                media == null ? null : media.durationMs(), media != null && media.thumbnailKey() != null,
                entry.source(), entry.favorite(), entry.trashedAt(), entry.version(), entry.createdAt(), entry.updatedAt());
    }
    private LibraryEntry require(UUID owner, UUID id) {
        return repository.entry(owner, id).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资产不存在或无权访问。"));
    }
    public EntryResponse update(UUID owner, UUID id, long expected, String name, LibraryEntry.Category category, boolean favorite) {
        String normalized = name(name);
        return tx.execute(ignored -> {
            LibraryEntry entry = require(owner, id);
            if (!repository.update(owner, id, expected, normalized, category, favorite, entry.trashedAt(), clock.instant()))
                throw conflict();
            return response(require(owner, id));
        });
    }
    public EntryResponse trash(UUID owner, UUID id, long expected, boolean restore) {
        return tx.execute(ignored -> {
            LibraryEntry entry = require(owner, id);
            if ((entry.trashedAt() == null) == restore)
                throw problem(HttpStatus.CONFLICT, "LIBRARY_ENTRY_STATE_INVALID", "资产的回收站状态已变化。");
            if (!repository.update(owner, id, expected, entry.name(), entry.category(), entry.favorite(),
                    restore ? null : clock.instant(), clock.instant())) throw conflict();
            return response(require(owner, id));
        });
    }
    public void delete(UUID owner, UUID id, long expected) {
        // Active commands hold their own hard-link pins; imported projects own independent bytes.
        Media removed = tx.execute(ignored -> {
            LibraryEntry entry = require(owner, id);
            if (entry.trashedAt() == null) throw problem(HttpStatus.CONFLICT, "LIBRARY_ENTRY_NOT_TRASHED", "请先移入回收站。");
            if (!repository.delete(owner, id, expected)) throw conflict();
            Media media = entry.fileId() == null ? null : repository.file(owner, entry.fileId());
            if (media != null) { repository.enqueueCleanup(media, clock.instant()); repository.deleteFile(owner, media.id()); }
            return media;
        });
        // File cleanup is durable and can resume after process or volume failures.
        if (removed != null) cleanupNext();
    }
    private ApiProblemException conflict() {
        return problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资产已变化，请刷新后重试。输入已保留。");
    }

    private LibraryEntry requireLocked(UUID owner, UUID id) {
        return repository.entryForUpdate(owner, id).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资产不存在或无权访问。"));
    }

    public LibraryCommand command(UUID owner, UUID id) {
        return repository.command(owner, id).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "转存命令不存在。"));
    }
    public LibraryCommand retry(UUID owner, UUID id) {
        return tx.execute(ignored -> {
            var before = command(owner, id);
            if (before.errorCode() != null && NON_RETRYABLE_CODES.contains(before.errorCode()))
                throw problem(HttpStatus.CONFLICT, "LIBRARY_COMMAND_NOT_RETRYABLE", "输入或版本已失效，请关闭窗口、核对最新草稿后重新选择。");
            if (before.status() != LibraryCommand.Status.FAILED || !repository.retry(owner, id, clock.instant()))
                throw problem(HttpStatus.CONFLICT, "LIBRARY_COMMAND_NOT_FAILED", "只有失败的本地转存可以重试。");
            return command(owner, id);
        });
    }

    private static final Duration CLEANUP_RETRY_DELAY = Duration.ofSeconds(30);

    public void cleanupNext() {
        var job = repository.cleanup(clock.instant());
        if (job.isEmpty()) return;
        var cleanup = job.get();
        try {
            if (cleanup.preparedImport() == null) archive.discardKeys(cleanup.owner(), cleanup.objectKey(), cleanup.thumbnailKey());
            else assets.discardUnregisteredLibraryImport(cleanup.owner(), cleanup.preparedImport());
            tx.executeWithoutResult(ignored -> repository.cleaned(cleanup.id()));
        }
        catch (RuntimeException failure) {
            tx.executeWithoutResult(ignored -> repository.deferCleanup(cleanup.id(), clock.instant().plus(CLEANUP_RETRY_DELAY)));
            LOGGER.warn("Library cleanup deferred for {}", cleanup.id());
        }
    }

    /** One short claim per worker; media decoding/copying occurs after the transaction closes. */
    public boolean processNext() {
        var claimed = shutdown.claimOrEmpty(() -> tx.execute(ignored -> repository.claim(clock.instant(), clock.instant().plus(LEASE))), java.util.Optional.<LibraryCommand>empty());
        if (claimed == null || claimed.isEmpty()) return false;
        var command = claimed.get();
        dev.agenvas.asset.domain.Asset preparedImport = null;
        try {
            JsonNode input = command.input();
            if (command.kind() == LibraryCommand.Kind.IMPORT || command.kind() == LibraryCommand.Kind.REFERENCE) {
                preparedImport = input.hasNonNull("pin")
                        ? assets.prepareLibraryImport(command.ownerId(), UUID.fromString(input.path("projectId").asText()),
                                command.id(), dev.agenvas.asset.domain.Asset.MediaKind.valueOf(input.path("kind").asText()),
                                archive.pinned(command.ownerId(), input.path("pin").asText())) : null;
                if (preparedImport != null && !preparedImport.sha256().equals(input.path("sha256").asText()))
                    throw new IllegalStateException("Frozen media checksum mismatch");
                var ready = preparedImport;
                tx.executeWithoutResult(ignored -> completeImport(command, ready));
            } else {
                Media media = input.hasNonNull("pin") ? archive.archive(command.ownerId(), command.id(),
                        dev.agenvas.asset.domain.Asset.MediaKind.valueOf(input.path("kind").asText()),
                        archive.pinned(command.ownerId(), input.path("pin").asText())) : null;
                if (media != null && !media.sha256().equals(input.path("sha256").asText()))
                    throw new IllegalStateException("Frozen media checksum mismatch");
                tx.executeWithoutResult(ignored -> completeSave(command, media));
            }
            archive.discardPin(command.ownerId(), input.path("pin").asText(null));
        } catch (RuntimeException failure) {
            LOGGER.warn("Library transfer {} failed: {}", command.id(), failure.getClass().getSimpleName());
            String code = failure instanceof ApiProblemException problem ? problem.code() : "LIBRARY_TRANSFER_FAILED";
            var rejectedImport = preparedImport;
            tx.executeWithoutResult(ignored -> {
                boolean finished = repository.finish(command, LibraryCommand.Status.FAILED, null,
                        code, failure instanceof ApiProblemException ? failure.getMessage() : "本地转存未完成，输入已保留。请检查存储后重试。", clock.instant());
                if (finished && NON_RETRYABLE_CODES.contains(code)) {
                    if (rejectedImport != null) repository.enqueueRejectedImport(command.ownerId(), rejectedImport, clock.instant());
                    repository.enqueuePinCleanup(command, clock.instant());
                }
            });
        }
        return true;
    }
    private void completeImport(LibraryCommand command, dev.agenvas.asset.domain.Asset prepared) {
        JsonNode input = command.input();
        UUID project = UUID.fromString(input.path("projectId").asText());
        if (prepared != null) assets.registerLibraryImport(command.ownerId(), prepared);
        var created = artifacts.createLibraryImport(command.ownerId(), project,
                Artifact.Kind.valueOf(input.path("kind").asText()), input.path("name").asText(), input.get("textContent"),
                prepared == null ? null : prepared.id());
        ObjectNode result = mapper.createObjectNode().put("artifactId", created.artifact().id().toString())
                .put("versionId", created.resourceDefaultVersion().id().toString());
        if (command.kind() == LibraryCommand.Kind.REFERENCE) {
            ReferenceDraft draft = mapper.treeToValue(input.path("draft"), ReferenceDraft.class);
            UUID item = UUID.fromString(input.path("itemId").asText());
            var inputs = new java.util.ArrayList<>(draft.mediaInputs() == null ? List.<MediaDraftService.SaveMediaInput>of() : draft.mediaInputs());
            inputs.add(new MediaDraftService.SaveMediaInput(created.resourceDefaultVersion().id(),
                    MediaDraft.InputRole.valueOf(input.path("role").asText()), input.path("color").asText()));
            Artifact.Kind kind = artifacts.get(command.ownerId(), project, items.requireArtifactItem(command.ownerId(), project, item).subjectId()).artifact().kind();
            var saved = drafts.save(command.ownerId(), project, item, draft.expectedVersion(), draft.prompt(), draft.parameters(),
                    draft.durationSeconds(), draft.capabilityId(), draft.videoInputMode(), inputs, draft.mentions());
            mediaTasks.validateLibraryReference(command.ownerId(), project, kind, saved);
            result.put("draftVersion", saved.version()).put("canvasItemId", item.toString());
        } else {
            canvas.apply(command.ownerId(), project, List.of(new CanvasService.PlaceArtifact(command.id(),
                    created.artifact().id(), input.path("x").decimalValue(), input.path("y").decimalValue(),
                    java.math.BigDecimal.valueOf(IMPORT_CARD_SIZE), java.math.BigDecimal.valueOf(IMPORT_CARD_SIZE), 0, null, false)));
            result.put("canvasItemId", command.id().toString());
        }
        repository.recordImport(project, created.resourceDefaultVersion().id(), UUID.fromString(input.path("entryId").asText()), input.path("source"));
        if (!repository.finish(command, LibraryCommand.Status.SUCCEEDED, result, null, null, clock.instant()))
            throw new IllegalStateException("Transfer lease superseded");
        repository.enqueuePinCleanup(command, clock.instant());
    }

    private void completeSave(LibraryCommand command, Media media) {
        JsonNode input = command.input();
        UUID source = input.hasNonNull("sourceVersionId") ? UUID.fromString(input.path("sourceVersionId").asText()) : null;
        LibraryEntry existing = source == null ? null : repository.source(command.ownerId(), source).orElse(null);
        if (existing == null) {
            if (media != null) repository.insertFile(media, clock.instant());
            var entry = new LibraryEntry(command.id(), command.ownerId(), input.path("name").asText(),
                    LibraryEntry.Category.valueOf(input.path("category").asText()), Artifact.Kind.valueOf(input.path("kind").asText()),
                    input.get("textContent"), media == null ? null : media.id(), source, input.path("source"),
                    false, null, 0, clock.instant(), clock.instant());
            if (!repository.insert(entry)) existing = repository.source(command.ownerId(), source).orElseThrow();
            else existing = entry;
        }
        if (media != null && !existing.id().equals(command.id())) {
            repository.deleteFile(command.ownerId(), media.id());
            repository.enqueueCleanup(media, clock.instant());
        }
        var result = mapper.createObjectNode().put("entryId", existing.id().toString())
                .put("alreadySaved", !existing.id().equals(command.id())).put("trashed", existing.trashedAt() != null);
        if (!repository.finish(command, LibraryCommand.Status.SUCCEEDED, result, null, null, clock.instant()))
            throw new IllegalStateException("Transfer lease superseded");
        repository.enqueuePinCleanup(command, clock.instant());
    }

    private String name(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH)
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "资产名称须为 1 至 160 个字符。");
        return name;
    }
    private String key(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_COMMAND_KEY_LENGTH)
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "命令键须为 1 至 200 个字符。");
        return value;
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private ApiProblemException problem(HttpStatus status, String code, String detail) {
        return new ApiProblemException(status, code, "资产操作未完成", detail, status == HttpStatus.CONFLICT);
    }
}
