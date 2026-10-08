package dev.agenvas.mediatemplate.application;

import static dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation.problem;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.*;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptHttpClient;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptRepository;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptRepository.ImportCommand;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.i18n.ApiMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Freeze URLs first, archive outside transactions, publish all project versions atomically. */
@Service
public class ThirdPartyPromptImportService {
    private final ThirdPartyPromptRepository repository;
    private final ThirdPartyPromptService prompts;
    private final ThirdPartyPromptHttpClient http;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final AssetService assets;
    private final ArtifactService artifacts;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    public ThirdPartyPromptImportService(ThirdPartyPromptRepository repository, ThirdPartyPromptService prompts,
            ThirdPartyPromptHttpClient http, ProjectService projects, ProjectEventService events, AssetService assets,
            ArtifactService artifacts, ObjectMapper mapper, Clock clock, PlatformTransactionManager transactions) {
        this.repository = repository; this.prompts = prompts; this.http = http; this.projects = projects; this.events = events;
        this.assets = assets; this.artifacts = artifacts; this.mapper = mapper; this.clock = clock; tx = new TransactionTemplate(transactions);
    }
    public record ImportedReference(UUID versionId, UUID assetId, String title, MediaKind kind, Role role, String thumbnailUrl) {}
    public record ImportResponse(UUID templateId, long templateVersion, TargetKind targetKind, String prompt,
            List<MediaTemplateService.ImportedImage> images, List<ImportedReference> references, String videoInputMode) {}
    public ImportResponse importPrompt(UUID owner, UUID project, String promptId, long expected, String commandKey) {
        if (expected < 0 || commandKey == null || commandKey.isBlank() || commandKey.length() > 200) throw ThirdPartyPromptValidation.invalid();
        String hash = Sha256.hex(mapper.writeValueAsString(List.of(promptId, expected)));
        ImportCommand command = tx.execute(ignored -> events.recordChange(owner, project, () -> {
            projects.requireActiveProject(owner, project);
            var replay = repository.command(owner, project, commandKey, false);
            if (replay.isPresent()) { checkHash(replay.get(), hash); return ProjectEventService.Change.unchanged(replay.get()); }
            var entry = prompts.get(promptId);
            if (entry.version() != expected) throw problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", ApiMessage.of("api.third-party.conflict"));
            if (entry.data().prompt().length() > MediaTemplateService.MAX_PROMPT_LENGTH)
                throw problem(HttpStatus.CONFLICT, "THIRD_PARTY_PROMPT_TOO_LONG", ApiMessage.of("api.third-party.promptTooLong"));
            if (entry.video() != null && !entry.video().missingReferences().isEmpty())
                throw problem(HttpStatus.CONFLICT, "THIRD_PARTY_REFERENCES_REQUIRED", ApiMessage.of("api.third-party.referencesRequired"));
            if (entry.video() != null && entry.video().videoMode() == VideoMode.text_to_image_to_video)
                throw problem(HttpStatus.CONFLICT, "THIRD_PARTY_IMAGE_STAGE_REQUIRED", ApiMessage.of("api.third-party.imageStage"));
            var accepted = new ImportCommand(UUID.randomUUID(), owner, hash, entry, null);
            repository.insertCommand(accepted, project, commandKey, clock.instant());
            return ProjectEventService.Change.unchanged(accepted);
        }).value());
        if (command.result() != null) return mapper.treeToValue(command.result(), ImportResponse.class);
        var snapshot = command.snapshot();
        List<Reference> references = snapshot.image() != null ? snapshot.image().referenceImageUrls().stream()
                .map(url -> new Reference(MediaKind.IMAGE, Role.REFERENCE, url)).toList() : snapshot.video().references();
        List<Asset> prepared = new ArrayList<>();
        for (int i = 0; i < references.size(); i++) {
            Reference ref = references.get(i);
            UUID id = UUID.nameUUIDFromBytes(("agenvas:third-party-import:v1:" + command.id() + ":" + i).getBytes(StandardCharsets.UTF_8));
            prepared.add(assets.prepareLibraryImport(owner, project, id, Asset.MediaKind.valueOf(ref.kind().name()),
                    () -> new ByteArrayInputStream(http.download(ref.url(), ref.kind() == MediaKind.IMAGE
                            ? (int) MediaTemplateService.MAX_IMAGE_UPLOAD_BYTES : ThirdPartyPromptHttpClient.MAX_MEDIA_BYTES))));
        }
        return tx.execute(ignored -> events.recordChange(owner, project, () -> {
            projects.requireActiveProject(owner, project);
            var locked = repository.command(owner, project, commandKey, true).orElseThrow(); checkHash(locked, hash);
            if (locked.result() != null) return ProjectEventService.Change.unchanged(mapper.treeToValue(locked.result(), ImportResponse.class));
            List<MediaTemplateService.ImportedImage> images = new ArrayList<>();
            List<ImportedReference> imported = new ArrayList<>();
            for (int i = 0; i < prepared.size(); i++) {
                Asset asset = prepared.get(i); Reference ref = references.get(i);
                assets.registerLibraryImport(owner, asset);
                String title = MediaTemplateService.importedImageTitle(snapshot.data().title(), i + 1);
                var artifact = artifacts.createLibraryImport(owner, project, Artifact.Kind.valueOf(ref.kind().name()), title, null, asset.id());
                UUID version = artifact.resourceDefaultVersion().id();
                String thumbnail = asset.mediaKind() == Asset.MediaKind.AUDIO ? "" : "/api/v1/projects/" + project + "/assets/" + asset.id() + "/thumbnail";
                imported.add(new ImportedReference(version, asset.id(), title, ref.kind(), ref.role(), thumbnail));
                if (ref.kind() == MediaKind.IMAGE) images.add(new MediaTemplateService.ImportedImage(version, asset.id(), title,
                        asset.contentType(), asset.byteSize(), asset.width(), asset.height(), thumbnail));
            }
            String mode = snapshot.video() == null ? null : snapshot.video().videoMode() == VideoMode.text_to_video ? "TEXT"
                    : references.stream().anyMatch(r -> r.role() == Role.START_FRAME) ? "START_END" : "GENERAL_REFERENCE";
            var result = new ImportResponse(UUID.nameUUIDFromBytes(snapshot.id().getBytes(StandardCharsets.UTF_8)), snapshot.version(), snapshot.targetKind(),
                    snapshot.data().prompt(), List.copyOf(images), List.copyOf(imported), mode);
            repository.complete(command.id(), mapper.valueToTree(result));
            return ProjectEventService.Change.unchanged(result);
        }).value());
    }
    private void checkHash(ImportCommand command, String hash) {
        if (!hash.equals(command.payloadHash())) throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ApiMessage.of("api.media-template.idempotencyConflict"));
    }
}
