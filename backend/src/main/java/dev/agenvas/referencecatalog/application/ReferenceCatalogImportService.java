package dev.agenvas.referencecatalog.application;

import static dev.agenvas.referencecatalog.application.ReferenceCatalogValidation.*;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.referencecatalog.infrastructure.ReferenceCatalogRepository;
import dev.agenvas.shared.crypto.Sha256;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Fixed command accepted before I/O; shared media lock covers preparation, publication and cleanup. */
@Service
public class ReferenceCatalogImportService {
    private final ReferenceCatalogService catalog; private final ReferenceCatalogRepository repository;
    private final ProjectService projects; private final ProjectEventService events; private final AssetService assets;
    private final ArtifactService artifacts; private final CanvasService canvas; private final ObjectMapper mapper; private final Clock clock;
    private final TransactionTemplate tx;
    private final ReferenceCatalogPrivateImageService privateImages;
    private final dev.agenvas.referencecatalog.infrastructure.CustomCostumeRepository costumes;
    private final dev.agenvas.asset.application.PrivateMediaArchive archive;
    public ReferenceCatalogImportService(ReferenceCatalogService catalog,ReferenceCatalogRepository repository,ProjectService projects,ProjectEventService events,
            AssetService assets,ArtifactService artifacts,CanvasService canvas,ObjectMapper mapper,Clock clock,PlatformTransactionManager transactions,ReferenceCatalogPrivateImageService privateImages,dev.agenvas.referencecatalog.infrastructure.CustomCostumeRepository costumes,dev.agenvas.asset.application.PrivateMediaArchive archive){
        this.costumes=costumes;this.archive=archive;this.privateImages=privateImages;this.catalog=catalog;this.repository=repository;this.projects=projects;this.events=events;this.assets=assets;this.artifacts=artifacts;this.canvas=canvas;
        this.mapper=mapper;this.clock=clock;this.tx=new TransactionTemplate(transactions);
    }
    public record Placement(double x,double y){}
    public record Input(String sourceId,UUID snapshotId,String entryId,String variantId,String sha256,String commandKey,Placement placement){}
    public record Accepted(String sourceId,UUID snapshotId,String entryId,String variantId,String sha256,Placement placement){}
    public record ImportResponse(UUID artifactId,UUID canvasItemId,UUID versionId,UUID assetId,String title,String contentType,long byteSize,Integer width,Integer height,String thumbnailUrl){}
    public ImportResponse importImage(UUID owner,UUID project,Input input){
        validate(input);
        var accepted=new Accepted(input.sourceId(),input.snapshotId(),input.entryId(),input.variantId(),input.sha256(),input.placement());
        String hash=Sha256.hex(mapper.writeValueAsString(accepted));
        var command=tx.execute(ignored -> events.recordChange(owner,project,()->{
            projects.requireActiveProject(owner,project);
            var replay=repository.command(owner,project,input.commandKey(),false);
            if(replay.isPresent()){checkHash(replay.get(),hash);return ProjectEventService.Change.unchanged(replay.get());}
            var selected=catalog.selection(input.sourceId(),input.snapshotId(),input.entryId(),input.variantId());
            if(!input.sha256().equals(selected.variant().sha256()))throw conflict();
            var created=new ReferenceCatalogRepository.Command(UUID.randomUUID(),hash,mapper.valueToTree(accepted),null);
            repository.insertCommand(created,owner,project,input.commandKey(),clock.instant());return ProjectEventService.Change.unchanged(created);
        }).value());
        if(command.result()!=null)return mapper.treeToValue(command.result(),ImportResponse.class);
        Accepted fixed=mapper.treeToValue(command.input(),Accepted.class);
        // The deterministic asset id recovers a crash after file installation, before DB publication.
        UUID assetId=UUID.nameUUIDFromBytes(("agenvas:reference-catalog:v1:"+command.id()).getBytes(StandardCharsets.UTF_8));
        return assets.withPreparedUpload(owner,project,assetId,Asset.MediaKind.IMAGE,()->{
            var selected=catalog.selection(fixed.sourceId(),fixed.snapshotId(),fixed.entryId(),fixed.variantId());
            return new ByteArrayInputStream(catalog.download(selected));
        },asset -> publishPrepared(owner,project,input.commandKey(),hash,command.id(),asset,fixed.sha256(),fixed.placement(),()->catalog.selection(fixed.sourceId(),fixed.snapshotId(),fixed.entryId(),fixed.variantId()).entry().title()));
    }
    public record PrivateRequest(UUID imageId,Placement placement){}
    public record PrivateAccepted(UUID imageId,String sha256,Placement placement){}
    public ImportResponse importPrivateImage(UUID owner,UUID project,UUID imageId,String commandKey,Placement placement){
        if(imageId==null || commandKey==null || commandKey.isBlank() || commandKey.length()>200)throw invalid();validatePlacement(placement);
        String hash=Sha256.hex(mapper.writeValueAsString(new PrivateRequest(imageId,placement)));
        var command=tx.execute(ignored->events.recordChange(owner,project,()->{
            projects.requireActiveProject(owner,project);var replay=repository.command(owner,project,commandKey,false);
            if(replay.isPresent()){checkHash(replay.get(),hash);return ProjectEventService.Change.unchanged(replay.get());}
            var owned=privateImages.requireOwned(owner,imageId);var accepted=new PrivateAccepted(imageId,owned.sha256(),placement);
            var created=new ReferenceCatalogRepository.Command(UUID.randomUUID(),hash,mapper.valueToTree(accepted),null);
            repository.insertCommand(created,owner,project,commandKey,clock.instant());return ProjectEventService.Change.unchanged(created);
        }).value());
        if(command.result()!=null)return mapper.treeToValue(command.result(),ImportResponse.class);
        PrivateAccepted fixed=mapper.treeToValue(command.input(),PrivateAccepted.class);
        UUID assetId=UUID.nameUUIDFromBytes(("agenvas:reference-catalog:v1:"+command.id()).getBytes(StandardCharsets.UTF_8));
        return assets.withPreparedUpload(owner,project,assetId,Asset.MediaKind.IMAGE,()->new ByteArrayInputStream(privateImages.read(owner,fixed.imageId())),
                asset->publishPrepared(owner,project,commandKey,hash,command.id(),asset,fixed.sha256(),fixed.placement(),()->privateImages.title(owner,fixed.imageId())));
    }
    public record CostumeRequest(UUID entryId,long expectedVersion,Placement placement){}
    /** Immutable accepted bytes/title outlive editing and soft deletion of their shared source entry. */
    public record CostumeAccepted(dev.agenvas.asset.application.PrivateMediaArchive.Media media,String title,Placement placement){}
    public ImportResponse importCostume(UUID owner,UUID project,UUID id,long expectedVersion,String commandKey,Placement placement){
        if(id==null || expectedVersion<0 || commandKey==null || commandKey.isBlank() || commandKey.length()>200)throw invalid();validatePlacement(placement);
        String hash=Sha256.hex(mapper.writeValueAsString(new CostumeRequest(id,expectedVersion,placement)));
        var command=tx.execute(ignored->events.recordChange(owner,project,()->{
            projects.requireActiveProject(owner,project);
            var replay=repository.command(owner,project,commandKey,false);
            if(replay.isPresent()){checkHash(replay.get(),hash);return ProjectEventService.Change.unchanged(replay.get());}
            var costume=costumes.lock(id).orElseThrow(ReferenceCatalogService::missing);
            if(costume.version()!=expectedVersion)throw ReferenceCatalogService.conflict();
            var accepted=new CostumeAccepted(costume.media(),costume.name(),placement);
            var created=new ReferenceCatalogRepository.Command(UUID.randomUUID(),hash,mapper.valueToTree(accepted),null);
            repository.insertCommand(created,owner,project,commandKey,clock.instant());return ProjectEventService.Change.unchanged(created);
        }).value());
        if(command.result()!=null)return mapper.treeToValue(command.result(),ImportResponse.class);
        var fixed=mapper.treeToValue(command.input(),CostumeAccepted.class);
        UUID assetId=UUID.nameUUIDFromBytes(("agenvas:reference-catalog:v1:"+command.id()).getBytes(StandardCharsets.UTF_8));
        return assets.withPreparedUpload(owner,project,assetId,Asset.MediaKind.IMAGE,()->{
            try{return java.nio.file.Files.newInputStream(archive.file(fixed.media().ownerId(),fixed.media(),false));}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot read archived costume",failure);}
        },asset->publishPrepared(owner,project,commandKey,hash,command.id(),asset,fixed.media().sha256(),fixed.placement(),fixed::title));
    }
    private ImportResponse publishPrepared(UUID owner,UUID project,String key,String hash,UUID command,Asset asset,String expectedSha,Placement placement,java.util.function.Supplier<String> titleSupplier){
        return tx.execute(ignored->events.recordChange(owner,project,()->{
            projects.requireActiveProject(owner,project);var locked=repository.command(owner,project,key,true).orElseThrow();checkHash(locked,hash);
            if(locked.result()!=null)return ProjectEventService.Change.unchanged(mapper.treeToValue(locked.result(),ImportResponse.class));
            String title=titleSupplier.get();if(!expectedSha.equals(asset.sha256()))throw conflict();
            assets.registerLibraryImport(owner,asset);
            int end=Math.min(title.length(),160);if(end<title.length() && Character.isHighSurrogate(title.charAt(end-1)) && Character.isLowSurrogate(title.charAt(end)))end--;
            title=title.substring(0,end);var artifact=artifacts.createLibraryImport(owner,project,Artifact.Kind.IMAGE,title,null,asset.id());
            UUID versionId=artifact.resourceDefaultVersion().id(),itemId=null;
            if(placement!=null){itemId=UUID.nameUUIDFromBytes(("agenvas:reference-canvas:v1:"+command).getBytes(StandardCharsets.UTF_8));
                canvas.apply(owner,project,List.of(new CanvasService.PlaceArtifact(itemId,artifact.artifact().id(),BigDecimal.valueOf(placement.x()),BigDecimal.valueOf(placement.y()),BigDecimal.valueOf(320),BigDecimal.valueOf(320),0,null,false,versionId)));}
            var result=new ImportResponse(artifact.artifact().id(),itemId,versionId,asset.id(),title,asset.contentType(),asset.byteSize(),asset.width(),asset.height(),"/api/v1/projects/"+project+"/assets/"+asset.id()+"/thumbnail");
            repository.complete(command,mapper.valueToTree(result));return ProjectEventService.Change.unchanged(result);
        }).value());
    }
    private static void validate(Input input){
        if(input==null || input.snapshotId()==null || input.sha256()==null || !input.sha256().matches("[a-f0-9]{64}") || input.commandKey()==null
                || input.commandKey().isBlank() || input.commandKey().length()>200)throw invalid();
        slug(input.sourceId());slug(input.entryId());slug(input.variantId());
        validatePlacement(input.placement());
    }
    private static void validatePlacement(Placement placement){
        if(placement!=null && (!Double.isFinite(placement.x()) || !Double.isFinite(placement.y()) || Math.abs(placement.x())>1_000_000 || Math.abs(placement.y())>1_000_000))throw invalid();
    }
    private static void checkHash(ReferenceCatalogRepository.Command command,String hash){
        if(!command.payloadHash().equals(hash))throw problem(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","api.reference-catalog.idempotencyConflict");
    }
    private static dev.agenvas.shared.error.ApiProblemException conflict(){return problem(HttpStatus.CONFLICT,"REFERENCE_CATALOG_IMAGE_CHANGED","api.reference-catalog.imageChanged");}
}
