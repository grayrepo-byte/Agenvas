package dev.agenvas.mediatemplate.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.application.PrivateMediaArchive;
import dev.agenvas.asset.application.PrivateMediaArchive.Media;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.mediatemplate.domain.MediaTemplate;
import dev.agenvas.mediatemplate.domain.MediaTemplate.Scope;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.infrastructure.MediaTemplateRepository;
import dev.agenvas.mediatemplate.infrastructure.MediaTemplateRepository.ImportCommand;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/** Presets never submit generation or alter model parameters, node selection or saved drafts. */
@Service
public class MediaTemplateService {
    public static final int MAX_NAME_LENGTH = 160;
    public static final int MAX_PROMPT_LENGTH = 20_000;
    public static final int MAX_IMAGES = 8;
    public static final int MAX_COMMAND_KEY_LENGTH = 200;
    public static final long MAX_IMAGE_UPLOAD_BYTES = 20L * 1024 * 1024;
    private static final int SCHEMA_VERSION = 1;
    private final MediaTemplateRepository repository;
    private final PrivateMediaArchive archive;
    private final AssetService assets;
    private final ArtifactService artifacts;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate reads;

    public MediaTemplateService(MediaTemplateRepository repository, PrivateMediaArchive archive,
            AssetService assets, ArtifactService artifacts, ProjectService projects, ProjectEventService events,
            ObjectMapper mapper, Clock clock, PlatformTransactionManager transactions) {
        this.repository = repository; this.archive = archive; this.assets = assets; this.artifacts = artifacts;
        this.projects = projects; this.events = events; this.mapper = mapper; this.clock = clock;
        tx = new TransactionTemplate(transactions);
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public record ImageResponse(UUID id, String contentType, long byteSize, int width, int height,
            String thumbnailUrl, String contentUrl) {}
    public record TemplateResponse(UUID id, String name, TargetKind targetKind, Scope scope,
            String prompt, List<ImageResponse> images, long version, Instant createdAt, Instant updatedAt) {}
    public record Page(List<TemplateResponse> items) {}
    public record ImportedImage(UUID versionId, UUID assetId, String title, String contentType,
            long byteSize, int width, int height, String thumbnailUrl) {}
    public record ImportResponse(UUID templateId, long templateVersion, TargetKind targetKind,
            String prompt, List<ImportedImage> images) {}
    public record MediaFile(Path path, String contentType, long size) {}
    /** Private fixed transfer input; archive metadata never enters public DTOs. */
    public record ImportSnapshot(int schemaVersion, MediaTemplate template, List<Media> images) {}
    record Draft(String name, TargetKind targetKind, String prompt, List<UUID> imageIds) {}

    public Page list(UUID owner, TargetKind kind, Scope scope, String query) {
        String search = query == null ? "" : query.trim();
        if (search.length() > MAX_NAME_LENGTH) throw invalid();
        return reads.execute(ignored -> new Page(repository.list(owner, kind, scope, search).stream().map(this::response).toList()));
    }
    public TemplateResponse get(UUID owner, UUID id) {
        return reads.execute(ignored -> response(visible(owner, id, false)));
    }
    public TemplateResponse create(UUID owner, Scope scope, String name, TargetKind kind, String prompt, List<UUID> images) {
        Draft draft = validateDraft(name, kind, prompt, images);
        return tx.execute(ignored -> {
            requireImages(owner, draft.imageIds(), List.of());
            Instant now = clock.instant();
            MediaTemplate value = new MediaTemplate(UUID.randomUUID(), owner, scope, kind,
                    draft.name(), draft.prompt(), draft.imageIds(), 0, now, now);
            repository.insert(value);
            return response(value);
        });
    }
    public TemplateResponse update(UUID owner, Scope scope, UUID id, long expected, String name,
            TargetKind kind, String prompt, List<UUID> images) {
        Draft draft = validateDraft(name, kind, prompt, images);
        return tx.execute(ignored -> {
            MediaTemplate current = editable(owner, scope, id);
            checkVersion(current, expected);
            // A different administrator may preserve an existing system template's images.
            requireImages(owner, draft.imageIds(), scope == Scope.SYSTEM ? current.imageIds() : List.of());
            MediaTemplate changed = new MediaTemplate(id, current.ownerId(), scope, kind,
                    draft.name(), draft.prompt(), draft.imageIds(), Math.addExact(expected, 1), current.createdAt(), clock.instant());
            if (!repository.update(changed, expected)) throw conflict();
            return response(changed);
        });
    }
    public void delete(UUID owner, Scope scope, UUID id, long expected) {
        tx.executeWithoutResult(ignored -> {
            checkVersion(editable(owner, scope, id), expected);
            if (!repository.delete(id, expected)) throw conflict();
        });
    }
    public ImageResponse upload(UUID owner, MultipartFile file) {
        if (file.isEmpty()) throw invalid();
        if (file.getSize() > MAX_IMAGE_UPLOAD_BYTES) throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "ASSET_TOO_LARGE", ApiMessage.of("api.media-template.imageTooLarge"));
        Media media;
        try (var input = file.getInputStream()) { media = archive.archive(owner, UUID.randomUUID(), Asset.MediaKind.IMAGE, input); }
        catch (IOException failure) { throw new IllegalStateException("Cannot read template image upload", failure); }
        return saveImage(media);
    }
    public ImageResponse copyImage(UUID owner, UUID project, UUID version) {
        var source = artifacts.requireImageVersionForTask(owner, project, version);
        var sourceFile = assets.get(owner, project, UUID.fromString(source.content().path("assetId").asText()));
        return saveImage(archive.archive(owner, UUID.randomUUID(), Asset.MediaKind.IMAGE, sourceFile.path()));
    }
    private ImageResponse saveImage(Media media) {
        try {
            return tx.execute(ignored -> { repository.insertImage(media, clock.instant()); return imageResponse(media); });
        } catch (RuntimeException failure) { archive.discard(media.ownerId(), media); throw failure; }
    }
    public MediaFile file(UUID owner, UUID id, boolean thumbnail) {
        Media media = reads.execute(ignored -> {
            Media image = repository.image(id, false).orElseThrow(this::notFound);
            if (!owner.equals(image.ownerId()) && !repository.sharedImage(id)) throw notFound();
            return image;
        });
        return new MediaFile(archive.file(media.ownerId(), media, thumbnail), thumbnail ? "image/png" : media.contentType(),
                thumbnail ? media.thumbnailByteSize() : media.byteSize());
    }
    public void deleteImage(UUID owner, UUID id) {
        Media image = tx.execute(ignored -> {
            Media media = repository.image(id, true).orElseThrow(this::notFound);
            if (!owner.equals(media.ownerId())) throw notFound();
            if (repository.imageInUse(id)) throw problem(HttpStatus.CONFLICT, "MEDIA_TEMPLATE_IMAGE_IN_USE", ApiMessage.of("api.media-template.imageInUse"));
            repository.deleteImage(id);
            return media;
        });
        archive.discard(owner, image);
    }

    /** Reserve a fixed snapshot first, copy bytes outside transactions, then publish atomically.
     * Repeating the same key resumes installation using stable asset IDs; concurrent replays
     * serialize the final publication on the project lock and return the persisted result.
     */
    public ImportResponse importTemplate(UUID owner, UUID project, UUID templateId, long expected, String commandKey) {
        String key = commandKey == null ? "" : commandKey.trim();
        if (expected < 0 || key.isEmpty() || key.length() > MAX_COMMAND_KEY_LENGTH) throw invalid();
        String hash = Sha256.hex(mapper.writeValueAsString(List.of(templateId, expected)));
        ImportCommand command = tx.execute(ignored -> events.recordChange(owner, project, () -> {
            projects.requireActiveProject(owner, project);
            var replay = repository.command(owner, project, key, false);
            if (replay.isPresent()) {
                checkHash(replay.get(), hash);
                return ProjectEventService.Change.unchanged(replay.get());
            }
            MediaTemplate template = visible(owner, templateId, true);
            checkVersion(template, expected);
            List<Media> sourceImages = template.imageIds().stream().sorted()
                    .map(id -> repository.image(id, true).orElseThrow(this::notFound)).toList();
            var imageById = sourceImages.stream().collect(java.util.stream.Collectors.toMap(Media::id, image -> image));
            ImportSnapshot snapshot = new ImportSnapshot(SCHEMA_VERSION, template,
                    template.imageIds().stream().map(imageById::get).toList());
            ImportCommand accepted = new ImportCommand(UUID.randomUUID(), owner, project, hash, mapper.valueToTree(snapshot), null);
            repository.insertCommand(accepted, key, template.imageIds(), clock.instant());
            return ProjectEventService.Change.unchanged(accepted);
        }).value());
        if (command.result() != null) return mapper.treeToValue(command.result(), ImportResponse.class);
        ImportSnapshot snapshot = mapper.treeToValue(command.input(), ImportSnapshot.class);
        List<Asset> prepared = new ArrayList<>();
        for (Media source : snapshot.images()) {
            UUID assetId = UUID.nameUUIDFromBytes(("agenvas:template-import:v1:" + command.id() + ":" + source.id())
                    .getBytes(StandardCharsets.UTF_8));
            prepared.add(assets.prepareLibraryImport(owner, project, assetId, Asset.MediaKind.IMAGE,
                    archive.file(source.ownerId(), source, false)));
        }
        return tx.execute(ignored -> events.recordChange(owner, project, () -> {
            projects.requireActiveProject(owner, project);
            ImportCommand locked = repository.command(owner, project, key, true).orElseThrow(this::notFound);
            checkHash(locked, hash);
            if (locked.result() != null) return ProjectEventService.Change.unchanged(mapper.treeToValue(locked.result(), ImportResponse.class));
            List<ImportedImage> imported = new ArrayList<>();
            for (int i = 0; i < prepared.size(); i++) {
                Asset media = prepared.get(i);
                assets.registerLibraryImport(owner, media);
                String title = importedImageTitle(snapshot.template().name(), i + 1);
                var artifact = artifacts.createTemplateImport(owner, project, title, media.id());
                repository.provenance(project, artifact.resourceDefaultVersion().id(), snapshot.template());
                imported.add(new ImportedImage(artifact.resourceDefaultVersion().id(), media.id(), title,
                        media.contentType(), media.byteSize(), media.width(), media.height(),
                        "/api/v1/projects/" + project + "/assets/" + media.id() + "/thumbnail"));
            }
            ImportResponse result = new ImportResponse(templateId, snapshot.template().version(), snapshot.template().targetKind(), snapshot.template().prompt(), List.copyOf(imported));
            repository.complete(command.id(), mapper.valueToTree(result));
            return ProjectEventService.Change.unchanged(result);
        }).value());
    }
    private void checkHash(ImportCommand command, String hash) {
        if (!command.payloadHash().equals(hash)) throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ApiMessage.of("api.media-template.idempotencyConflict"));
    }
    private MediaTemplate visible(UUID owner, UUID id, boolean lock) {
        MediaTemplate template = repository.find(id, lock).orElseThrow(this::notFound);
        if (template.scope() != Scope.SYSTEM && !template.ownerId().equals(owner)) throw notFound();
        return template;
    }
    private MediaTemplate editable(UUID owner, Scope scope, UUID id) {
        MediaTemplate template = repository.find(id, true).orElseThrow(this::notFound);
        if (template.scope() != scope || (scope == Scope.PERSONAL && !template.ownerId().equals(owner))) throw notFound();
        return template;
    }
    private void requireImages(UUID owner, List<UUID> images, List<UUID> preserved) {
        images.stream().sorted().forEach(id -> {
            Media media = repository.image(id, true).orElseThrow(this::notFound);
            if (!owner.equals(media.ownerId()) && !preserved.contains(id)) throw notFound();
        });
    }
    /** Adds an image label without splitting a supplementary Unicode character at the title limit. */
    static String importedImageTitle(String templateName, int position) {
        String suffix = " · " + position;
        int end = Math.min(templateName.length(), MAX_NAME_LENGTH - suffix.length());
        if (end < templateName.length() && Character.isHighSurrogate(templateName.charAt(end - 1))
                && Character.isLowSurrogate(templateName.charAt(end))) end--;
        return templateName.substring(0, end).stripTrailing() + suffix;
    }

    static Draft validateDraft(String name, TargetKind kind, String prompt, List<UUID> imageIds) {
        String title = name == null ? "" : name.trim();
        if (title.isBlank() || title.length() > MAX_NAME_LENGTH || kind == null || prompt == null
                || prompt.isBlank() || prompt.length() > MAX_PROMPT_LENGTH || imageIds == null
                || imageIds.size() > MAX_IMAGES || imageIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(imageIds).size() != imageIds.size()) throw invalid();
        return new Draft(title, kind, prompt, List.copyOf(imageIds));
    }
    private void checkVersion(MediaTemplate template, long expected) { if (expected < 0 || template.version() != expected) throw conflict(); }
    private TemplateResponse response(MediaTemplate value) {
        return new TemplateResponse(value.id(), value.name(), value.targetKind(), value.scope(), value.prompt(),
                value.imageIds().stream().map(id -> imageResponse(repository.image(id, false).orElseThrow(this::notFound))).toList(),
                value.version(), value.createdAt(), value.updatedAt());
    }
    private ImageResponse imageResponse(Media media) {
        String root = "/api/v1/media-templates/images/" + media.id();
        return new ImageResponse(media.id(), media.contentType(), media.byteSize(), media.width(), media.height(), root + "/thumbnail", root + "/content");
    }
    private ApiProblemException notFound() { return problem(HttpStatus.NOT_FOUND, "MEDIA_TEMPLATE_NOT_FOUND", ApiMessage.of("api.media-template.notFound")); }
    private static ApiProblemException invalid() { return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.media-template.invalid")); }
    private static ApiProblemException conflict() { return problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", ApiMessage.of("api.media-template.versionConflict")); }
    private static ApiProblemException problem(HttpStatus status, String code, ApiMessage detail) {
        return new ApiProblemException(status, code, ApiMessage.of("api.media-template.problem"), detail, false);
    }
}
