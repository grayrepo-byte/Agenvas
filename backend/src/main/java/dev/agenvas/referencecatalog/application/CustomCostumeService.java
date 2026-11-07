package dev.agenvas.referencecatalog.application;

import static dev.agenvas.referencecatalog.application.ReferenceCatalogValidation.*;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.application.PrivateMediaArchive;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import dev.agenvas.referencecatalog.infrastructure.CustomCostumeRepository;
import dev.agenvas.referencecatalog.infrastructure.CustomCostumeRepository.Costume;
import dev.agenvas.referencecatalog.infrastructure.ReferenceCatalogRepository.Command;
import dev.agenvas.shared.crypto.Sha256;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Accepted saves pin an exact project image before independently archiving it; all file I/O is outside transactions. */
@Service
public class CustomCostumeService {
    public static final String SOURCE_ID="self-created";
    private final CustomCostumeRepository repository;
    private final PrivateMediaArchive archive;
    private final AssetService assets;
    private final ArtifactService artifacts;
    private final CanvasItemQueryService items;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    public CustomCostumeService(CustomCostumeRepository repository,PrivateMediaArchive archive,AssetService assets,ArtifactService artifacts,
            CanvasItemQueryService items,ProjectService projects,ProjectEventService events,ObjectMapper mapper,Clock clock,PlatformTransactionManager transactions){
        this.repository=repository;this.archive=archive;this.assets=assets;this.artifacts=artifacts;this.items=items;this.projects=projects;this.events=events;this.mapper=mapper;this.clock=clock;this.tx=new TransactionTemplate(transactions);
    }
    public record Save(UUID canvasItemId,UUID sourceVersionId,long expectedCanvasItemVersion,String name,String category,List<String> tags,String commandKey){}
    public record Accepted(String name,String category,List<String> tags,String pin,String sha256){}
    public record ImageFile(Path path,String contentType,long byteSize){}
    public ReferenceCatalogService.EntryView save(UUID owner,UUID project,Save input){
        if(input==null || input.canvasItemId()==null || input.sourceVersionId()==null || input.expectedCanvasItemVersion()<0)throw invalid();
        validateMetadata(input.name(),input.category(),input.tags());validateKey(input.commandKey());
        String hash=Sha256.hex(mapper.writeValueAsString(input));
        Command command=tx.execute(ignored->events.recordChange(owner,project,()->{
            projects.requireActiveProject(owner,project);
            var replay=repository.command(owner,project,input.commandKey(),false);
            replay.ifPresent(c->checkHash(c,hash));
            return ProjectEventService.Change.unchanged(replay.orElse(null));
        }).value());
        if(command==null){
            UUID asset=tx.execute(ignored->events.recordChange(owner,project,()->ProjectEventService.Change.unchanged(sourceAsset(owner,project,input))).value());
            var file=assets.get(owner,project,asset);UUID candidate=UUID.randomUUID();
            String pin=archive.pin(owner,candidate,file.path());
            try{
                var accepted=new Accepted(input.name().strip(),category(input.category()),List.copyOf(input.tags()),pin,file.asset().sha256());
                command=tx.execute(ignored->events.recordChange(owner,project,()->{
                    projects.requireActiveProject(owner,project);
                    var replay=repository.command(owner,project,input.commandKey(),false);
                    if(replay.isPresent()){checkHash(replay.get(),hash);return ProjectEventService.Change.unchanged(replay.get());}
                    sourceAsset(owner,project,input);
                    var created=new Command(candidate,hash,mapper.valueToTree(accepted),null);
                    repository.insertCommand(created,owner,project,input.commandKey(),clock.instant());return ProjectEventService.Change.unchanged(created);
                }).value());
                if(!candidate.equals(command.id()))archive.discardPin(owner,pin);
            }catch(RuntimeException failure){archive.discardPin(owner,pin);throw failure;}
        }
        if(command.result()!=null)return mapper.treeToValue(command.result(),ReferenceCatalogService.EntryView.class);
        Accepted fixed=mapper.treeToValue(command.input(),Accepted.class);
        var media=archive.archive(owner,command.id(),Asset.MediaKind.IMAGE,archive.pinned(owner,fixed.pin()));
        if(!media.sha256().equals(fixed.sha256()))throw ReferenceCatalogService.conflict();
        Command acceptedCommand=command;
        // Keeping the accepted pin makes delayed same-command workers restart-safe without a deletion race.
        return tx.execute(ignored->events.recordChange(owner,project,()->{
            projects.requireActiveProject(owner,project);
            var locked=repository.command(owner,project,input.commandKey(),true).orElseThrow();checkHash(locked,hash);
            if(locked.result()!=null)return ProjectEventService.Change.unchanged(mapper.treeToValue(locked.result(),ReferenceCatalogService.EntryView.class));
            var costume=new Costume(acceptedCommand.id(),owner,fixed.name(),fixed.category(),fixed.tags(),media,0);
            repository.insert(costume,clock.instant());var result=view(costume);repository.complete(locked.id(),mapper.valueToTree(result));
            return ProjectEventService.Change.unchanged(result);
        }).value());
    }
    private UUID sourceAsset(UUID owner,UUID project,Save input){
        projects.requireActiveProject(owner,project);var item=items.requireArtifactItem(owner,project,input.canvasItemId());
        if(item.version()!=input.expectedCanvasItemVersion() || !input.sourceVersionId().equals(item.selectedVersionId()))throw ReferenceCatalogService.conflict();
        var version=artifacts.requireImageVersionForTask(owner,project,input.sourceVersionId());
        if(!version.artifactId().equals(item.subjectId()))throw ReferenceCatalogService.missing();
        UUID asset=UUID.fromString(version.content().path("assetId").asText());assets.requireReadyMedia(owner,project,asset,Asset.MediaKind.IMAGE);return asset;
    }
    public ReferenceCatalogService.EntryView update(UUID id,long expected,String name,String category,List<String> tags){
        validateMetadata(name,category,tags);if(expected<0)throw invalid();
        return tx.execute(ignored->{require(id);if(!repository.update(id,expected,name.strip(),category(category),List.copyOf(tags),clock.instant()))throw ReferenceCatalogService.conflict();return view(require(id));});
    }
    public void delete(UUID id,long expected){if(expected<0)throw invalid();tx.executeWithoutResult(ignored->{require(id);if(!repository.delete(id,expected,clock.instant()))throw ReferenceCatalogService.conflict();});}
    public ReferenceCatalogService.EntryView get(UUID id){return view(require(id));}
    public ImageFile image(UUID id){var c=require(id);return new ImageFile(archive.file(c.owner(),c.media(),false),c.media().contentType(),c.media().byteSize());}
    private Costume require(UUID id){return repository.find(id).orElseThrow(ReferenceCatalogService::missing);}
    public static ReferenceCatalogService.EntryView view(Costume c){
        var m=c.media();var variant=new ReferenceCatalogService.VariantView("front","Front","front","front",Map.of(),null,"/api/v1/reference-catalog/costumes/"+c.id()+"/image",m.sha256(),m.byteSize(),m.contentType(),"AVAILABLE",true,null,true,false,null);
        return new ReferenceCatalogService.EntryView(SOURCE_ID,c.id(),c.id().toString(),Kind.COSTUME,c.name(),Map.of(),"",c.category(),Map.of(),Map.of(),c.tags(),Map.of(),Map.of(),Map.of(),"",false,null,true,null,List.of(variant),Origin.SELF_CREATED,c.version());
    }
    private static String category(String category){return category==null?"":category.strip();}
    private static void validateMetadata(String name,String category,List<String> tags){if(name==null || name.isBlank() || name.length()>160 || category!=null && category.length()>100 || tags==null || tags.size()>32 || tags.stream().anyMatch(t->t==null || t.isBlank() || t.length()>64) || new HashSet<>(tags).size()!=tags.size())throw invalid();}
    private static void validateKey(String key){if(key==null || key.isBlank() || key.length()>200)throw invalid();}
    private static void checkHash(Command command,String hash){if(!command.payloadHash().equals(hash))throw problem(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","api.reference-catalog.idempotencyConflict");}
}
