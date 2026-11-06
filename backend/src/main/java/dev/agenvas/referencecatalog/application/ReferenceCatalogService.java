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
    private final ReferenceCatalogRepository repository; private final ReferenceCatalogGitHubClient github;
    private final ReferenceCatalogParser parser; private final Clock clock; private final TransactionTemplate tx;
    public ReferenceCatalogService(ReferenceCatalogRepository repository,ReferenceCatalogGitHubClient github,ReferenceCatalogParser parser,Clock clock,PlatformTransactionManager transactions){
        this.repository=repository;this.github=github;this.parser=parser;this.clock=clock;this.tx=new TransactionTemplate(transactions);
    }
    public List<Source> sources(){return repository.sources();}
    public Source source(String id){return repository.source(id).orElseThrow(ReferenceCatalogService::missing);}
    public Source create(String id,String name,String owner,String repo,String ref,String manifestPath,Format format,boolean approved){
        slug(id);slug(owner);slug(repo);ref(ref);
        if(name==null || name.isBlank() || name.length()>160 || format==null)throw invalid();
        String file=manifestPath==null || manifestPath.isBlank() ? format==Format.OPEN_POSE_COSTUME?"public/data/catalog.json":"agenvas-reference-pack.json" : path(manifestPath);
        if(owner.equalsIgnoreCase("hongye007") && repo.equalsIgnoreCase("open-pose-costume") && format!=Format.OPEN_POSE_COSTUME)throw invalid();
        Source s=new Source(id,name,owner,repo,ref,file,format,true,approved,0,null,"NEVER",null,null,null);
        if(!repository.insert(s,clock.instant()))throw conflict();return source(id);
    }
    public Source update(String id,long expected,boolean enabled,boolean approved){
        source(id);if(expected<0 || !repository.update(id,expected,enabled,approved))throw conflict();return source(id);
    }
    public record SyncResult(String state,Source source,UUID snapshotId,int entryCount,int variantCount){}
    public SyncResult sync(String id,long expected){
        Source selected=source(id); if(selected.version()!=expected)throw conflict();
        if(!selected.enabled())return new SyncResult("DISABLED",selected,selected.currentSnapshotId(),0,0);
        UUID token=UUID.randomUUID();
        if(!repository.claim(id,expected,token,clock.instant())) {
            if(source(id).version()!=expected)throw conflict();
            return new SyncResult("RUNNING",source(id),selected.currentSnapshotId(),0,0);
        }
        try {
            String commit=github.resolve(selected);
            byte[] bytes=github.file(selected,commit,selected.manifestPath(),ReferenceCatalogParser.MAX_METADATA_BYTES);
            Snapshot snapshot=parser.parse(selected,commit,bytes);
            boolean published=Boolean.TRUE.equals(tx.execute(ignored -> repository.publish(id,expected,token,snapshot,clock.instant())));
            return new SyncResult(published?"SUCCESS":"SUPERSEDED",source(id),published?snapshot.id():source(id).currentSnapshotId(),
                    published?snapshot.entries().size():0,published?snapshot.entries().stream().mapToInt(e->e.variants().size()).sum():0);
        } catch(ApiProblemException failure) {
            repository.fail(id,token,failure.code());return new SyncResult("FAILED",source(id),selected.currentSnapshotId(),0,0);
        } catch(RuntimeException failure) {
            repository.fail(id,token,"REFERENCE_CATALOG_SYNC_FAILED");throw failure;
        }
    }
    public record Filters(List<String> categories,List<String> tags,List<String> styles,List<String> collections,List<String> angles){}
    public record VariantView(String id,String label,String view,String angle,Map<String,String> localizedLabel,String externalUrl,
            String previewUrl,String sha256,Long byteSize,String contentType,String availability,boolean importAllowed,String disabledReason){}
    public record EntryView(String sourceId,UUID snapshotId,String id,Kind kind,String title,Map<String,String> localizedTitle,String code,
            String category,Map<String,String> localizedCategory,Map<String,String> series,List<String> tags,Map<String,Map<String,String>> localizedTags,Map<String,String> localizedKeywords,
            Map<String,String> facets,String collection,boolean extended,String externalUrl,boolean importAllowed,String disabledReason,List<VariantView> variants){}
    public record Page(List<EntryView> items,long total,int offset,int limit,Filters filters){}
    public Page list(Kind kind,String sourceId,String query,String category,String tag,String style,String collection,Boolean extended,String angle,int offset,int limit){
        if(offset<0 || limit<1 || limit>100 || query==null || query.length()>200)throw invalid();
        if(sourceId!=null)source(sourceId);
        List<EntryView> all=new ArrayList<>();
        for(Source source:sources()) {
            if(sourceId!=null && !source.id().equals(sourceId) || source.currentSnapshotId()==null)continue;
            Snapshot snapshot=repository.snapshot(source.id(),source.currentSnapshotId()).orElseThrow(ReferenceCatalogService::missing);
            for(Entry entry:snapshot.entries())if(kind==null || entry.kind()==kind)all.add(view(source,snapshot,entry));
        }
        Filters filters=new Filters(distinct(all.stream().map(EntryView::category).toList()),distinct(all.stream().flatMap(e->e.tags().stream()).toList()),
                distinct(all.stream().map(e->e.facets().getOrDefault("style","")).toList()),distinct(all.stream().map(EntryView::collection).toList()),
                distinct(all.stream().flatMap(e->e.variants().stream().map(VariantView::angle)).toList()));
        List<String> needles=java.util.Arrays.stream(normalize(query).strip().split("\\s+")).filter(s->!s.isBlank()).toList();
        List<EntryView> matching=all.stream().filter(e -> (category==null || category.equals(e.category())) && (tag==null || e.tags().contains(tag))
                && (style==null || style.equals(e.facets().get("style"))) && (collection==null || collection.equals(e.collection()))
                && (extended==null || extended==e.extended()) && (angle==null || e.variants().stream().anyMatch(v->angle.equals(v.angle())))
                && needles.stream().allMatch(needle -> searchable(e).contains(needle))).toList();
        return new Page(matching.stream().skip(offset).limit(limit).toList(),matching.size(),offset,limit,filters);
    }
    private static String searchable(EntryView e){return normalize(e.id()+" "+e.code()+" "+e.title()+" "+e.localizedTitle().values()+" "+e.localizedKeywords().values()+" "+e.localizedCategory().values()+" "+e.series().values()+" "+e.tags()+" "+e.localizedTags().values());}
    private static String normalize(String value){return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    private static List<String> distinct(List<String> values){return values.stream().filter(s->!s.isBlank()).distinct().sorted().toList();}
    private EntryView view(Source source,Snapshot snapshot,Entry e){
        List<VariantView> variants=e.variants().stream().map(v->{boolean allowed=ReferenceCatalogParser.importable(source,snapshot,v);
            return new VariantView(v.id(),v.label(),v.view(),v.angle(),v.localizedLabel(),v.externalUrl(),allowed?previewUrl(source.id(),snapshot.id(),e.id(),v.id()):null,
                    v.sha256(),v.byteSize(),v.contentType(),allowed?"AVAILABLE":"LINK_ONLY",allowed,allowed?null:disabled(source));}).toList();
        boolean allowed=variants.stream().anyMatch(VariantView::importAllowed);
        return new EntryView(source.id(),snapshot.id(),e.id(),e.kind(),e.title(),e.localizedTitle(),e.code(),e.category(),e.localizedCategory(),e.series(),e.tags(),e.localizedTags(),e.localizedKeywords(),
                e.facets(),e.collection(),e.extended(),e.externalUrl(),allowed,allowed?null:disabled(source),variants);
    }
    public record Selection(Source source,Snapshot snapshot,Entry entry,Variant variant){}
    public Selection selection(String sourceId,UUID snapshotId,String entryId,String variantId){
        Source s=source(sourceId);Snapshot snapshot=repository.snapshot(sourceId,snapshotId).orElseThrow(ReferenceCatalogService::missing);
        Entry e=snapshot.entries().stream().filter(x->x.id().equals(entryId)).findFirst().orElseThrow(ReferenceCatalogService::missing);
        Variant v=e.variants().stream().filter(x->x.id().equals(variantId)).findFirst().orElseThrow(ReferenceCatalogService::missing);
        if(!ReferenceCatalogParser.importable(s,snapshot,v))throw problem(HttpStatus.CONFLICT,disabled(s),"api.reference-catalog.rightsRequired");
        return new Selection(s,snapshot,e,v);
    }
    public byte[] download(Selection selected){
        // Rights are checked again immediately before every fetch, independently of stale client metadata.
        selection(selected.source().id(),selected.snapshot().id(),selected.entry().id(),selected.variant().id());
        var v=selected.variant();byte[] bytes=github.file(selected.source(),selected.snapshot().commit(),v.path(),MAX_IMAGE_BYTES);
        image(bytes,v.sha256(),v.byteSize(),v.contentType());return bytes;
    }
    public static String previewUrl(String source,UUID snapshot,String entry,String variant){return "/api/v1/reference-catalog/sources/"+source+"/snapshots/"+snapshot+"/entries/"+entry+"/variants/"+variant+"/preview";}
    private static String disabled(Source source){return !source.enabled()?"REFERENCE_CATALOG_SOURCE_DISABLED":source.format()==Format.OPEN_POSE_COSTUME?"REFERENCE_CATALOG_LINK_ONLY":"REFERENCE_CATALOG_RIGHTS_REQUIRED";}
    public static ApiProblemException missing(){return problem(HttpStatus.NOT_FOUND,"REFERENCE_CATALOG_NOT_FOUND","api.reference-catalog.notFound");}
    public static ApiProblemException conflict(){return problem(HttpStatus.CONFLICT,"VERSION_CONFLICT","api.reference-catalog.conflict");}
}
