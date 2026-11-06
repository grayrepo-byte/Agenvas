package dev.agenvas.characterlibrary.application;

import static dev.agenvas.characterlibrary.application.CharacterLibraryModels.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.application.PrivateMediaArchive;
import dev.agenvas.asset.application.PrivateMediaArchive.Media;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.character.application.CharacterModels;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.characterlibrary.infrastructure.CharacterLibraryRepository;
import dev.agenvas.characterlibrary.infrastructure.CharacterLibraryRepository.Job;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Account profiles and durable copies. All cross-module work uses application boundaries. */
@Service
public class CharacterLibraryService {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Set<String> TERMINAL = Set.of("VERSION_CONFLICT", "CHARACTER_PREVIEW_CONFLICT", "CHARACTER_ARCHIVED",
            "CHARACTER_LIBRARY_ARCHIVED", "RESOURCE_NOT_FOUND", "VALIDATION_ERROR", "PROJECT_ARCHIVED", "PROJECT_DELETED");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CharacterLibraryService.class);
    private final CharacterLibraryRepository repository;
    private final PrivateMediaArchive archive;
    private final LibraryService library;
    private final AssetService assets;
    private final ArtifactService artifacts;
    private final CharacterService characters;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final ShutdownGate shutdown;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    public CharacterLibraryService(CharacterLibraryRepository repository, PrivateMediaArchive archive,
            LibraryService library, AssetService assets, ArtifactService artifacts, CharacterService characters,
            ProjectService projects, ProjectEventService events, ShutdownGate shutdown, Clock clock,
            ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.repository=repository; this.archive=archive; this.library=library; this.assets=assets;
        this.artifacts=artifacts; this.characters=characters; this.projects=projects; this.events=events;
        this.shutdown=shutdown; this.clock=clock; this.mapper=mapper; tx=new TransactionTemplate(transactions);
    }
    public Page list(UUID owner, String query, boolean archived, UUID cursor, int limit) {
        if (limit<1 || limit>100 || query!=null && query.length()>160) throw invalid();
        var all=repository.profiles(owner,query,archived,cursor,limit+1);
        var page=all.stream().limit(limit).toList();
        return new Page(page,all.size()>limit?page.get(page.size()-1).id():null);
    }
    public Profile get(UUID owner, UUID id) { return require(owner,id,false); }
    public Profile save(UUID owner, UUID id, Save request) {
        if (request==null || request.commandId()==null || request.expectedVersion()<0) throw invalid();
        String hash=digest("SAVE",id,request);
        return tx.execute(ignored -> {
            Job replay=replay(owner,request.commandId(),hash);
            if (replay!=null) return mapper.treeToValue(replay.result().path("profile"),Profile.class);
            Job job=accepted(owner,request.commandId(),hash,Kind.SAVE,document());
            if (!repository.reserve(job,clock.instant())) return mapper.treeToValue(replay(owner,request.commandId(),hash).result().path("profile"),Profile.class);
            Content content=validate(owner,request.content());
            Profile result=write(owner,id,request.expectedVersion(),content,false);
            if (!repository.finish(job,Status.SUCCEEDED,document().set("profile",mapper.valueToTree(result)),null,clock.instant())) throw conflict();
            return result;
        });
    }
    public Profile archive(UUID owner, UUID id, Archive request) {
        if (request==null || request.commandId()==null || request.expectedVersion()<1) throw invalid();
        String hash=digest("ARCHIVE",id,request);
        return tx.execute(ignored -> {
            Job replay=replay(owner,request.commandId(),hash);
            if (replay!=null) return mapper.treeToValue(replay.result().path("profile"),Profile.class);
            Job job=accepted(owner,request.commandId(),hash,Kind.ARCHIVE,document());
            if (!repository.reserve(job,clock.instant())) return mapper.treeToValue(replay(owner,request.commandId(),hash).result().path("profile"),Profile.class);
            Profile before=require(owner,id,true);
            Profile result=write(owner,id,request.expectedVersion(),before.content(),true);
            if (!repository.finish(job,Status.SUCCEEDED,document().set("profile",mapper.valueToTree(result)),null,clock.instant())) throw conflict();
            return result;
        });
    }
    private Profile write(UUID owner, UUID id, long expected, Content content, boolean archived) {
        Profile before=repository.profile(owner,id,true).orElse(null);
        if (before!=null && before.archived()) throw archived();
        if ((before==null?0:before.version())!=expected) throw conflict();
        var now=clock.instant();
        Profile result=new Profile(id,content,expected+1,archived,before==null?now:before.createdAt(),now);
        if (!repository.save(owner,result,expected)) throw conflict();
        return result;
    }
    public Command media(UUID owner, MediaSource request) {
        if (request==null || request.commandId()==null || request.sourceType()==null) throw invalid();
        String hash=digest("MEDIA",null,request);
        Job replay=replay(owner,request.commandId(),hash); if (replay!=null) return replay.response();
        ObjectNode input=document(); var pins=new ArrayList<JsonNode>();
        try {
            UUID mediaId=stable(owner,request.commandId(),"media");
            if (request.sourceType()==SourceType.LIBRARY_ENTRY) {
                if (request.entryId()==null || request.expectedEntryVersion()==null || request.expectedEntryVersion()<0 || request.projectId()!=null || request.versionId()!=null) throw invalid();
                var pinned=library.pinForCharacter(owner,request.entryId(),request.expectedEntryVersion(),UUID.randomUUID());
                pins.add(pin(mediaId,pinned.media().kind(),pinned.pinKey(),pinned.contentHash()));
            } else {
                if (request.projectId()==null || request.versionId()==null || request.entryId()!=null || request.expectedEntryVersion()!=null) throw invalid();
                pins.add(projectPin(owner,request.projectId(),request.versionId(),mediaId));
            }
            input.set("pins",mapper.valueToTree(pins));
            if (request.sourceType()==SourceType.PROJECT_VERSION) {
                return tx.execute(ignored -> events.recordChange(owner,request.projectId(),() -> {
                    projects.get(owner,request.projectId());
                    return ProjectEventService.Change.unchanged(accept(owner,request.commandId(),hash,Kind.MEDIA,input));
                }).value());
            }
            return tx.execute(ignored -> accept(owner,request.commandId(),hash,Kind.MEDIA,input));
        } catch (RuntimeException failure) { cleanupUnreferencedPins(owner,request.commandId(),pins); throw failure; }
    }
    public Command upload(UUID owner, UUID commandId, Asset.MediaKind kind, MultipartFile file) {
        if (commandId==null || kind==null || kind==Asset.MediaKind.VIDEO || file==null || file.isEmpty() || file.getSize()>assets.maximumUploadBytes(kind)) throw invalid();
        Media temporary;
        try (var stream=file.getInputStream()) { temporary=archive.archive(owner,UUID.randomUUID(),kind,stream); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read upload",failure); }
        List<JsonNode> pins=new ArrayList<>();
        try {
            String hash=digest("UPLOAD",null,List.of(kind,temporary.sha256()));
            Job replay=replay(owner,commandId,hash); if (replay!=null) return replay.response();
            pins.add(pin(stable(owner,commandId,"media"),kind,archive.pin(owner,UUID.randomUUID(),archive.file(owner,temporary,false)),temporary.sha256()));
            ObjectNode input=document(); input.set("pins",mapper.valueToTree(pins));
            return tx.execute(ignored -> accept(owner,commandId,hash,Kind.MEDIA,input));
        } catch (RuntimeException failure) { cleanupUnreferencedPins(owner,commandId,pins); throw failure; }
        finally { archive.discard(owner,temporary); }
    }
    public Command publish(UUID owner, UUID id, Publish request) {
        if (request==null || request.commandId()==null || request.expectedVersion()<0 || request.sourceProjectId()==null || request.sourceCharacterId()==null || request.sourceCharacterVersion()<1 || request.identityVersionIds()==null || request.identityVersionIds().size()>14) throw invalid();
        String hash=digest("PUBLISH",id,request);
        Job replay=replay(owner,request.commandId(),hash); if (replay!=null) return replay.response();
        var source=characters.getRevision(owner,request.sourceProjectId(),request.sourceCharacterId(),request.sourceCharacterVersion());
        var pins=new ArrayList<JsonNode>(); Map<UUID,UUID> mediaIds=new LinkedHashMap<>();
        java.util.function.Function<UUID,UUID> copy=version -> mediaIds.computeIfAbsent(version,v -> {
            UUID mid=stable(owner,request.commandId(),v.toString()); pins.add(projectPin(owner,request.sourceProjectId(),v,mid)); return mid;
        });
        try {
            var identity=request.identityVersionIds().stream().map(v -> new View(copy.apply(v),"Identity baseline","" )).toList();
            var looks=source.content().looks().stream().map(l -> new Look(l.id(),l.name(),l.clothing(),l.expression(),l.description(),l.views().stream().map(v -> new View(copy.apply(v.versionId()),v.purpose(),v.description())).toList())).toList();
            var voice=source.content().voice();
            Content content=normalize(new Content(source.content().name(),source.content().description(),"",identity,looks,
                    voice==null?null:new Voice(voice.versionId()==null?null:copy.apply(voice.versionId()),voice.speakerId(),voice.description())));
            checkProfileVersion(owner,id,request.expectedVersion());
            ObjectNode input=document().put("profileId",id.toString()).put("expectedVersion",request.expectedVersion());
            input.set("content",mapper.valueToTree(content)); input.set("pins",mapper.valueToTree(pins));
            return tx.execute(ignored -> events.recordChange(owner,request.sourceProjectId(),() -> {
                projects.get(owner,request.sourceProjectId()); checkProfileVersion(owner,id,request.expectedVersion());
                return ProjectEventService.Change.unchanged(accept(owner,request.commandId(),hash,Kind.PUBLISH,input));
            }).value());
        } catch (RuntimeException failure) { cleanupUnreferencedPins(owner,request.commandId(),pins); throw failure; }
    }
    public TransferPreview preview(UUID owner, UUID project, Transfer request) {
        return tx.execute(ignored -> prepare(owner,project,request,false));
    }
    private TransferPreview prepare(UUID owner, UUID project, Transfer request, boolean lock) {
        if (request==null || request.libraryId()==null || request.characterId()==null || request.libraryVersion()<1 || request.expectedCharacterVersion()<0) throw invalid();
        projects.requireActiveProject(owner,project);
        Profile profile=require(owner,request.libraryId(),lock);
        if (profile.archived()) throw archived();
        if (profile.version()!=request.libraryVersion()) throw conflict();
        var before=characters.list(owner,project).characters().stream().filter(p -> p.id().equals(request.characterId())).findFirst().orElse(null);
        if ((before==null?0:before.version())!=request.expectedCharacterVersion() || before!=null && before.archived()) throw conflict();
        var link=repository.links(owner,project).stream().filter(l -> l.character().equals(request.characterId())).findFirst().orElse(null);
        if (before!=null && (link==null || !link.library().equals(request.libraryId()))) throw conflict();
        var affected=before==null?List.<CharacterModels.AffectedItem>of():characters.previewChange(owner,project,before.id(),new CharacterModels.SaveCharacter(UUID.randomUUID(),before.version(),before.content())).affectedItems();
        var changes=List.of(before==null?"CREATE_PROJECT_CHARACTER":"UPDATE_PROJECT_CHARACTER","COPY_INDEPENDENT_MEDIA","EXISTING_SHOTS_REQUIRE_REVIEW");
        String hash=digest("TRANSFER",project,List.of(request,profile.content(),before==null?"NEW":before,affected,link==null?"NEW":link));
        return new TransferPreview(hash,profile.id(),profile.version(),request.characterId(),request.expectedCharacterVersion(),profile.content(),before,affected,changes);
    }
    public Command importProfile(UUID owner, UUID project, Import request) {
        if (request==null || request.commandId()==null || request.previewHash()==null || !request.previewHash().matches("[a-f0-9]{64}")) throw invalid();
        String hash=digest("IMPORT",project,request);
        Job replay=replay(owner,request.commandId(),hash); if (replay!=null) { projects.get(owner,project); return replay.response(); }
        var preview=preview(owner,project,request.transfer());
        if (!preview.previewHash().equals(request.previewHash())) throw conflict();
        var pins=new ArrayList<JsonNode>();
        try {
            for (UUID id:mediaIds(preview.content())) {
                Media source=media(owner,id);
                UUID assetId=stable(owner,request.commandId(),id.toString());
                pins.add(pin(assetId,source.kind(),archive.pin(owner,UUID.randomUUID(),archive.file(owner,source,false)),source.sha256()).put("sourceMediaId",id.toString()));
            }
            ObjectNode input=document().put("projectId",project.toString()).put("previewHash",request.previewHash());
            input.set("transfer",mapper.valueToTree(request.transfer())); input.set("content",mapper.valueToTree(preview.content())); input.set("pins",mapper.valueToTree(pins));
            return tx.execute(ignored -> events.recordChange(owner,project,() -> {
                if (!prepare(owner,project,request.transfer(),true).previewHash().equals(request.previewHash())) throw conflict();
                return ProjectEventService.Change.unchanged(accept(owner,request.commandId(),hash,Kind.IMPORT,input));
            }).value());
        } catch (RuntimeException failure) { cleanupUnreferencedPins(owner,request.commandId(),pins); throw failure; }
    }
    public Links links(UUID owner, UUID project) {
        projects.get(owner,project); var profiles=characters.list(owner,project).characters();
        return new Links(repository.links(owner,project).stream().map(link -> {
            Profile current=require(owner,link.library(),false);
            long pv=profiles.stream().filter(p -> p.id().equals(link.character())).findFirst().orElseThrow(this::missing).version();
            return new Link(link.character(),link.library(),link.version(),current.version(),current.archived(),pv,current.version()>link.version());
        }).toList());
    }
    public Command command(UUID owner, UUID id) { return repository.job(owner,id,false).orElseThrow(this::missing).response(); }
    public Command retry(UUID owner, UUID id) {
        return tx.execute(ignored -> {
            Job job=repository.job(owner,id,true).orElseThrow(this::missing);
            if (job.status()!=Status.FAILED || TERMINAL.contains(job.errorCode()) || !repository.retry(owner,id,clock.instant())) throw conflict();
            return command(owner,id);
        });
    }
    public LibraryService.MediaFile file(UUID owner, UUID id, boolean thumbnail) {
        Media media=media(owner,id);
        return new LibraryService.MediaFile(archive.file(owner,media,thumbnail),thumbnail?"image/png":media.contentType(),thumbnail?media.thumbnailByteSize():media.byteSize());
    }
    public boolean processNext() {
        var claimed=shutdown.claimOrEmpty(() -> tx.execute(ignored -> repository.claim(clock.instant(),clock.instant().plus(LEASE))),java.util.Optional.<Job>empty());
        if (claimed==null || claimed.isEmpty()) return false;
        Job job=claimed.get(); Map<UUID,Media> copied=new LinkedHashMap<>(); Map<UUID,Asset> prepared=new LinkedHashMap<>();
        try {
            for (JsonNode pin:job.input().path("pins")) {
                UUID id=UUID.fromString(pin.path("mediaId").asText()); var kind=Asset.MediaKind.valueOf(pin.path("kind").asText());
                if (job.kind()==Kind.IMPORT) {
                    Asset asset=assets.prepareLibraryImport(job.owner(),UUID.fromString(job.input().path("projectId").asText()),id,kind,archive.pinned(job.owner(),pin.path("pin").asText()),() -> active(job));
                    if (!asset.sha256().equals(pin.path("sha256").asText())) throw new IllegalStateException("Frozen project media hash mismatch");
                    prepared.put(UUID.fromString(pin.path("sourceMediaId").asText()),asset);
                } else {
                    Media media=archive.archive(job.owner(),id,kind,archive.pinned(job.owner(),pin.path("pin").asText()),() -> active(job));
                    if (!media.sha256().equals(pin.path("sha256").asText())) throw new IllegalStateException("Frozen account media hash mismatch");
                    copied.put(id,media);
                }
            }
            tx.executeWithoutResult(ignored -> complete(job,copied,prepared));
        } catch (RuntimeException failure) {
            LOG.warn("Character library operation {} failed: {}",job.id(),failure.getClass().getSimpleName());
            String code=failure instanceof ApiProblemException api?api.code():"CHARACTER_LIBRARY_TRANSFER_FAILED";
            tx.executeWithoutResult(ignored -> {
                if (repository.finish(job,Status.FAILED,null,code,clock.instant()) && TERMINAL.contains(code)) repository.cleanup(job,job.input(),clock.instant());
            });
        }
        return true;
    }
    private void complete(Job job, Map<UUID,Media> copied, Map<UUID,Asset> prepared) {
        Job live=repository.job(job.owner(),job.id(),true).orElseThrow(this::missing);
        if (live.epoch()!=job.epoch() || live.status()!=Status.ARCHIVING) throw conflict();
        if (job.kind()==Kind.IMPORT) {
            UUID project=UUID.fromString(job.input().path("projectId").asText());
            events.recordChange(job.owner(),project,() -> {
                Transfer transfer=mapper.treeToValue(job.input().path("transfer"),Transfer.class);
                if (!prepare(job.owner(),project,transfer,true).previewHash().equals(job.input().path("previewHash").asText())) throw conflict();
                Map<UUID,UUID> versions=new HashMap<>();
                prepared.forEach((source,asset) -> {
                    assets.registerLibraryImport(job.owner(),asset);
                    var imported=artifacts.createLibraryImport(job.owner(),project,Artifact.Kind.valueOf(asset.mediaKind().name()),"Character reference",null,asset.id());
                    versions.put(source,imported.resourceDefaultVersion().id());
                });
                Content content=mapper.treeToValue(job.input().path("content"),Content.class);
                var profile=characters.save(job.owner(),project,transfer.characterId(),new CharacterModels.SaveCharacter(stable(job.owner(),job.id(),"project-profile-command"),transfer.expectedCharacterVersion(),projectContent(job.owner(),transfer.libraryId(),content,versions)));
                repository.link(job.owner(),project,profile.id(),transfer.libraryId(),transfer.libraryVersion(),clock.instant());
                ObjectNode result=document().put("characterId",profile.id().toString()).put("characterVersion",profile.version()).put("libraryId",transfer.libraryId().toString()).put("libraryVersion",transfer.libraryVersion());
                if (!repository.finish(job,Status.SUCCEEDED,result,null,clock.instant())) throw conflict();
                repository.cleanup(job,job.input().deepCopy(),clock.instant());
                return ProjectEventService.Change.unchanged(result);
            });
        } else {
            copied.values().forEach(media -> repository.media(job.owner(),media,clock.instant()));
            ObjectNode result=document();
            if (job.kind()==Kind.MEDIA) result.put("mediaId",copied.keySet().iterator().next().toString());
            else {
                Content content=validate(job.owner(),mapper.treeToValue(job.input().path("content"),Content.class));
                Profile profile=write(job.owner(),UUID.fromString(job.input().path("profileId").asText()),job.input().path("expectedVersion").asLong(),content,false);
                result.set("profile",mapper.valueToTree(profile));
            }
            if (!repository.finish(job,Status.SUCCEEDED,result,null,clock.instant())) throw conflict();
            repository.cleanup(job,job.input(),clock.instant());
        }
    }
    /** Cleanup eligibility is persisted; no source project lookup is needed after publication. */
    public void cleanupNext() {
        var next=repository.cleanup(clock.instant()); if (next.isEmpty()) return; var cleanup=next.get();
        try {
            Job job=repository.job(cleanup.owner(),cleanup.command(),false).orElseThrow(this::missing);
            if (job.status()!=Status.SUCCEEDED && !(job.status()==Status.FAILED && TERMINAL.contains(job.errorCode()))) return;
            for (JsonNode pin:cleanup.payload().path("pins")) {
                archive.discardPin(cleanup.owner(),pin.path("pin").asText());
                if (job.status()==Status.FAILED) {
                    UUID id=UUID.fromString(pin.path("mediaId").asText()); var kind=Asset.MediaKind.valueOf(pin.path("kind").asText());
                    if (job.kind()==Kind.IMPORT) {
                        // Metadata is recoverable from stable planned bytes even after a crash.
                        // Project cleanup is best-effort after deletion; account pins are already independent.
                        assets.cleanupPlannedCharacterImport(cleanup.owner(),UUID.fromString(job.input().path("projectId").asText()),id,kind,
                                () -> !active(job));
                    } else archive.cleanupUnregistered(cleanup.owner(),id,kind,() -> repository.media(cleanup.owner(),id).isEmpty() && !active(job));
                }
            }
            tx.executeWithoutResult(ignored -> repository.cleaned(cleanup));
        } catch (RuntimeException failure) { tx.executeWithoutResult(ignored -> repository.defer(cleanup,clock.instant().plusSeconds(30))); }
    }
    private boolean active(Job job) { return repository.job(job.owner(),job.id(),false).map(live -> live.status()==Status.ARCHIVING && live.epoch()==job.epoch()).orElse(false); }
    private ObjectNode projectPin(UUID owner, UUID project, UUID version, UUID id) {
        var exact=artifacts.requireVersionById(owner,project,version);
        var artifact=artifacts.get(owner,project,exact.artifactId()).artifact();
        if (artifact.kind()!=Artifact.Kind.IMAGE && artifact.kind()!=Artifact.Kind.AUDIO) throw invalid();
        var file=assets.get(owner,project,UUID.fromString(exact.content().path("assetId").asText()));
        var kind=Asset.MediaKind.valueOf(artifact.kind().name());
        return pin(id,kind,archive.pin(owner,UUID.randomUUID(),file.path()),file.asset().sha256());
    }
    private ObjectNode pin(UUID id, Asset.MediaKind kind, String key, String sha) { return mapper.createObjectNode().put("mediaId",id.toString()).put("kind",kind.name()).put("pin",key).put("sha256",sha); }
    /** Retain every pin on an uncertain outcome until a transaction barrier proves it unused. */
    private void cleanupUnreferencedPins(UUID owner, UUID commandId, List<JsonNode> pins) {
        if (pins.isEmpty()) return;
        // A surrounding caller transaction has not resolved yet; retaining input is the only
        // safe action until that caller commits or rolls back. Do not wait on our own lock.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return;
        var reconcile=new TransactionTemplate(tx.getTransactionManager());
        reconcile.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            reconcile.executeWithoutResult(ignored -> {
                repository.lockCommand(owner,commandId);
                Job durable=repository.job(owner,commandId,false).orElse(null);
                Set<String> referenced=new HashSet<>();
                if (durable!=null) durable.input().path("pins").forEach(pin -> referenced.add(pin.path("pin").asText()));
                for (JsonNode pin:pins) {
                    String key=pin.path("pin").asText();
                    if (!referenced.contains(key)) archive.discardPin(owner,key);
                }
            });
        } catch (RuntimeException unavailable) {
            // Database unavailability is not evidence of rollback. In particular, never delete
            // an accepted operation's only durable input merely because COMMIT was not acknowledged.
            LOG.warn("Character library pin reconciliation deferred for {}",commandId);
        }
    }
    private void discardPins(UUID owner, List<JsonNode> pins) { pins.forEach(pin -> archive.discardPin(owner,pin.path("pin").asText())); }
    private Command accept(UUID owner, UUID id, String hash, Kind kind, ObjectNode input) {
        repository.lockCommand(owner,id);
        Job job=accepted(owner,id,hash,kind,input);
        if (!repository.reserve(job,clock.instant())) {
            Job replay=replay(owner,id,hash);
            List<JsonNode> pins=new ArrayList<>(); input.path("pins").forEach(pins::add); discardPins(owner,pins);
            return replay.response();
        }
        return job.response();
    }
    private Job accepted(UUID owner, UUID id, String hash, Kind kind, JsonNode input) { return new Job(owner,id,hash,kind,input,Status.ACCEPTED,0,null,null); }
    private Job replay(UUID owner, UUID id, String hash) {
        Job job=repository.job(owner,id,false).orElse(null);
        if (job!=null && !job.hash().equals(hash)) throw problem(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
        return job;
    }
    private void checkProfileVersion(UUID owner, UUID id, long expected) {
        Profile before=repository.profile(owner,id,false).orElse(null);
        if (before!=null && before.archived()) throw archived();
        if ((before==null?0:before.version())!=expected) throw conflict();
    }
    private Profile require(UUID owner, UUID id, boolean lock) { return repository.profile(owner,id,lock).orElseThrow(this::missing); }
    private Media media(UUID owner, UUID id) { return repository.media(owner,id).orElseThrow(this::missing); }
    private Content validate(UUID owner, Content content) {
        Content normalized=normalize(content);
        normalized.identityViews().forEach(v -> requireMedia(owner,v.mediaId(),Asset.MediaKind.IMAGE));
        normalized.looks().forEach(l -> l.views().forEach(v -> requireMedia(owner,v.mediaId(),Asset.MediaKind.IMAGE)));
        if (normalized.voice()!=null && normalized.voice().mediaId()!=null) requireMedia(owner,normalized.voice().mediaId(),Asset.MediaKind.AUDIO);
        return normalized;
    }
    private void requireMedia(UUID owner, UUID id, Asset.MediaKind kind) { if (media(owner,id).kind()!=kind) throw invalid(); }
    private Content normalize(Content value) {
        if (value==null || value.identityViews()==null || value.looks()==null || value.identityViews().size()>14 || value.looks().size()>20) throw invalid();
        String name=text(value.name(),160,true),persona=text(value.persona(),4000,false),appearance=text(value.appearance(),4000,false);
        if (description(persona,appearance).length()>4000) throw invalid();
        List<View> identity=views(value.identityViews());
        if (identity.stream().anyMatch(view -> view.purpose().length()>101)) throw invalid();
        Set<UUID> ids=new HashSet<>(); var looks=new ArrayList<Look>();
        for (Look look:value.looks()) {
            if (look==null || look.id()==null || !ids.add(look.id())) throw invalid();
            List<View> views=views(look.views()); Set<UUID> count=new HashSet<>(); identity.forEach(v -> count.add(v.mediaId())); views.forEach(v -> count.add(v.mediaId()));
            if (count.size()>14) throw invalid();
            for (View view:views) identity.stream().filter(i -> i.mediaId().equals(view.mediaId())).findFirst()
                    .ifPresent(i -> { if (mergedDescription(i.description(),view).length()>2000) throw invalid(); });
            looks.add(new Look(look.id(),text(look.name(),160,true),text(look.clothing(),2000,false),text(look.expression(),2000,false),text(look.description(),4000,false),views));
        }
        Voice voice=value.voice();
        if (voice!=null) {
            String speaker=voice.speakerId()==null || voice.speakerId().isBlank()?null:voice.speakerId().trim();
            if (speaker!=null && !speaker.matches("[A-Za-z0-9_-]{1,120}") || voice.mediaId()==null && speaker==null) throw invalid();
            voice=new Voice(voice.mediaId(),speaker,text(voice.description(),2000,false));
        }
        return new Content(name,persona,appearance,identity,List.copyOf(looks),voice);
    }
    private List<View> views(List<View> values) {
        if (values==null || values.size()>14) throw invalid(); Set<UUID> ids=new HashSet<>(); var views=new ArrayList<View>();
        for (View view:values) { if (view==null || view.mediaId()==null || !ids.add(view.mediaId())) throw invalid(); views.add(new View(view.mediaId(),text(view.purpose(),120,true),text(view.description(),2000,false))); }
        return List.copyOf(views);
    }
    private Set<UUID> mediaIds(Content value) {
        Set<UUID> ids=new java.util.LinkedHashSet<>(); value.identityViews().forEach(v -> ids.add(v.mediaId()));
        value.looks().forEach(l -> l.views().forEach(v -> ids.add(v.mediaId()))); if (value.voice()!=null && value.voice().mediaId()!=null) ids.add(value.voice().mediaId()); return ids;
    }
    private CharacterModels.CharacterContent projectContent(UUID owner, UUID libraryId, Content content, Map<UUID,UUID> versions) {
        List<Look> looks=content.looks().isEmpty()?List.of(new Look(stable(owner,libraryId,"default-look"),"Default","","","",List.of())):content.looks();
        var projected=looks.stream().map(look -> {
            Map<UUID,CharacterModels.CharacterView> views=new LinkedHashMap<>();
            content.identityViews().forEach(v -> views.put(v.mediaId(),new CharacterModels.CharacterView(versions.get(v.mediaId()),"Identity baseline: "+v.purpose(),v.description())));
            look.views().forEach(v -> {
                var identity=views.get(v.mediaId());
                if (identity==null) views.put(v.mediaId(),new CharacterModels.CharacterView(versions.get(v.mediaId()),v.purpose(),v.description()));
                else views.put(v.mediaId(),new CharacterModels.CharacterView(identity.versionId(),identity.purpose(),
                        mergedDescription(identity.description(),v)));
            });
            return new CharacterModels.CharacterLook(look.id(),look.name(),look.clothing(),look.expression(),look.description(),List.copyOf(views.values()));
        }).toList();
        Voice voice=content.voice();
        return new CharacterModels.CharacterContent(content.name(),description(content.persona(),content.appearance()),projected,
                voice==null?null:new CharacterModels.CharacterVoice(voice.mediaId()==null?null:versions.get(voice.mediaId()),voice.speakerId(),voice.description()));
    }
    private String mergedDescription(String identityDescription, View lookView) {
        return identityDescription+"\n\nView purpose: "+lookView.purpose()+"\n"+lookView.description();
    }
    private String description(String persona,String appearance) { return appearance.isEmpty()?persona:persona+"\n\nAppearance:\n"+appearance; }
    private String text(String value,int max,boolean required) { if (value==null || value.length()>max || required && value.isBlank() || value.indexOf('\0')>=0 || value.indexOf('\uFFFC')>=0) throw invalid(); return value.trim(); }
    private UUID stable(UUID owner, UUID command, String suffix) { return UUID.nameUUIDFromBytes(("character-library:"+owner+":"+command+":"+suffix).getBytes(StandardCharsets.UTF_8)); }
    private ObjectNode document() { return mapper.createObjectNode().put("schemaVersion",1); }
    private String digest(String kind, UUID target, Object value) { ObjectNode node=mapper.createObjectNode().put("kind",kind).put("target",target==null?"":target.toString()); node.set("request",mapper.valueToTree(value)); return Sha256.hex(canonical(node)); }
    private String canonical(JsonNode value) {
        if (value.isObject()) { var fields=new TreeMap<String,JsonNode>(); value.properties().forEach(e -> fields.put(e.getKey(),e.getValue())); return "{"+fields.entrySet().stream().map(e -> mapper.writeValueAsString(e.getKey())+":"+canonical(e.getValue())).collect(java.util.stream.Collectors.joining(","))+"}"; }
        if (value.isArray()) { List<String> values=new ArrayList<>(); value.forEach(e -> values.add(canonical(e))); return "["+String.join(",",values)+"]"; }
        return mapper.writeValueAsString(value);
    }
    private ApiProblemException invalid() { return problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR"); }
    private ApiProblemException conflict() { return problem(HttpStatus.CONFLICT,"VERSION_CONFLICT"); }
    private ApiProblemException archived() { return problem(HttpStatus.CONFLICT,"CHARACTER_LIBRARY_ARCHIVED"); }
    private ApiProblemException missing() { return problem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"); }
    private ApiProblemException problem(HttpStatus status,String code) { return new ApiProblemException(status,code,ApiMessage.of("api.character.invalid"),ApiMessage.of("api.character.invalid"),false); }
}
