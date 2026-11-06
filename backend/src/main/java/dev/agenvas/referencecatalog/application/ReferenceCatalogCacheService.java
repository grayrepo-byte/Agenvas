package dev.agenvas.referencecatalog.application;

import static dev.agenvas.referencecatalog.application.ReferenceCatalogValidation.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalogCacheJob;
import dev.agenvas.referencecatalog.domain.ReferenceCatalogCacheJob.*;
import dev.agenvas.referencecatalog.infrastructure.ReferenceCatalogCacheRepository;
import dev.agenvas.referencecatalog.infrastructure.ReferenceCatalogImageCache;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Explicit shared-cache controls; project imports remain independent immutable Asset copies. */
@Service
public class ReferenceCatalogCacheService {
    private static final int MAX_ITEMS=5000,MAX_ACTIVE_JOBS=20,BATCH_SIZE=10;
    private final ReferenceCatalogService catalog;private final ReferenceCatalogCacheRepository repository;private final ReferenceCatalogImageCache cache;
    private final dev.agenvas.referencecatalog.infrastructure.ReferenceCatalogPrivateImageRepository privateImages;
    private final Clock clock;private final ObjectMapper mapper;private final TransactionTemplate tx;private final boolean recovery;
    public ReferenceCatalogCacheService(ReferenceCatalogService catalog,ReferenceCatalogCacheRepository repository,ReferenceCatalogImageCache cache,Clock clock,ObjectMapper mapper,
            PlatformTransactionManager transactions,dev.agenvas.referencecatalog.infrastructure.ReferenceCatalogPrivateImageRepository privateImages,@Value("${agenvas.recovery-mode:false}") boolean recovery){
        this.privateImages=privateImages;this.catalog=catalog;this.repository=repository;this.cache=cache;this.clock=clock;this.mapper=mapper;this.tx=new TransactionTemplate(transactions);this.recovery=recovery;
    }
    public record Failure(String entryId,String variantId,String code){}
    public record Job(UUID id,String sourceId,UUID snapshotId,String state,int totalCount,int readyCount,int failedCount,long totalBytes,long readyBytes,Instant createdAt,Instant updatedAt,List<Failure> failures){}
    public Job create(UUID owner,String source,UUID snapshot,List<String> entryIds,String key){
        writable();if(key==null || key.isBlank() || key.length()>200 || source==null || snapshot==null || entryIds==null || entryIds.size()>MAX_ITEMS)throw invalid();
        slug(source);entryIds.forEach(ReferenceCatalogValidation::slug);if(new HashSet<>(entryIds).size()!=entryIds.size())throw invalid();
        String hash=Sha256.hex(mapper.writeValueAsString(List.of(source,snapshot,entryIds.stream().sorted().toList())));
        return tx.execute(ignored->{repository.lockQueue();var replay=repository.command(owner,key);if(replay.isPresent()){
            if(!hash.equals(replay.get().payloadHash()))throw problem(HttpStatus.CONFLICT,"REFERENCE_CATALOG_CACHE_JOB_CONFLICT","api.reference-catalog.cacheJobConflict");return view(replay.get());}
            if(repository.activeCount()>=MAX_ACTIVE_JOBS)throw limit();
            var selections=catalog.selections(source,snapshot,entryIds);
            if(selections.size()>MAX_ITEMS || selections.stream().mapToLong(s->s.variant().byteSize()).sum()>cache.quotaBytes())throw limit();
            List<Item> items=selections.stream().map(s->new Item(s.entry().id(),s.variant().id(),s.variant().sha256(),s.variant().byteSize(),ItemState.QUEUED,null)).toList();
            Instant now=clock.instant();var job=new ReferenceCatalogCacheJob(UUID.randomUUID(),owner,source,snapshot,key,hash,State.QUEUED,items,null,null,now,now);
            repository.insert(job);return view(job);
        });
    }
    public List<Job> list(){return repository.list().stream().map(this::view).toList();}
    public Job get(UUID id){return view(required(id,false));}
    public Job retry(UUID id){writable();return tx.execute(ignored->{repository.lockQueue();var job=required(id,true);
        if(job.state()==State.CANCELLED)throw ReferenceCatalogService.conflict();
        if(job.state()!=State.PARTIAL_FAILED)return view(job);
        if(repository.activeCount()>=MAX_ACTIVE_JOBS)throw limit();
        // A retry cannot restore withdrawn permission, even if previous successes are on disk.
        catalog.selections(job.sourceId(),job.snapshotId(),job.items().stream().map(Item::entryId).distinct().toList());
        repository.retry(id,job.items().stream().map(Item::retry).toList(),clock.instant());return view(required(id,false));
    });}
    public Job cancel(UUID id){writable();required(id,false);repository.cancel(id,clock.instant());return get(id);}
    public ReferenceCatalogImageCache.Stats stats(){return cache.stats(privateImages.protectedHashes());}
    public ReferenceCatalogImageCache.Removed clear(String source,UUID snapshot,List<String> entryIds){
        writable();if(entryIds==null || entryIds.size()>MAX_ITEMS || source==null && (snapshot!=null || !entryIds.isEmpty()))throw invalid();
        Set<String> hashes=source==null?null:catalog.cachedImageHashes(source,snapshot,entryIds);
        repository.cancelMatching(source,snapshot,clock.instant());return cache.clearProtecting(hashes,privateImages::protectedHashes);
    }
    /** One bounded batch; a restart reclaims an expired lease and skips READY items. */
    public boolean processNext(){
        if(recovery)return false;var claim=tx.execute(ignored->repository.claim(clock.instant()));if(claim==null || claim.isEmpty())return false;
        var job=claim.get();List<Item> items=new ArrayList<>(job.items());int processed=0;
        for(int i=0;i<items.size() && processed<BATCH_SIZE;i++){
            Item item=items.get(i);if(item.state()!=ItemState.QUEUED)continue;
            if(!repository.owns(job.id(),job.leaseToken(),clock.instant()))return true;
            try{
                var selected=catalog.selection(job.sourceId(),job.snapshotId(),item.entryId(),item.variantId());
                if(!item.sha256().equals(selected.variant().sha256()) || item.byteSize()!=selected.variant().byteSize())throw invalid();
                catalog.download(selected,()->{if(!repository.owns(job.id(),job.leaseToken(),clock.instant()))throw new IllegalStateException("Cache job claim no longer active");});items.set(i,item.ready());
            }catch(RuntimeException failure){items.set(i,item.failed(failure instanceof ApiProblemException problem?problem.code():"REFERENCE_CATALOG_CACHE_DOWNLOAD_FAILED"));}
            processed++;
            if(!repository.progress(job.id(),job.leaseToken(),items,State.RUNNING,clock.instant()))return true;
        }
        State state=items.stream().anyMatch(i->i.state()==ItemState.QUEUED)?State.QUEUED:items.stream().anyMatch(i->i.state()==ItemState.FAILED)?State.PARTIAL_FAILED:State.SUCCEEDED;
        repository.progress(job.id(),job.leaseToken(),items,state,clock.instant());return true;
    }
    private ReferenceCatalogCacheJob required(UUID id,boolean lock){if(id==null)throw invalid();return repository.get(id,lock).orElseThrow(ReferenceCatalogService::missing);}
    private Job view(ReferenceCatalogCacheJob job){var items=job.items();return new Job(job.id(),job.sourceId(),job.snapshotId(),job.state().name(),items.size(),(int)items.stream().filter(i->i.state()==ItemState.READY).count(),
            (int)items.stream().filter(i->i.state()==ItemState.FAILED).count(),items.stream().mapToLong(Item::byteSize).sum(),items.stream().filter(i->i.state()==ItemState.READY).mapToLong(Item::byteSize).sum(),job.createdAt(),job.updatedAt(),
            items.stream().filter(i->i.state()==ItemState.FAILED).map(i->new Failure(i.entryId(),i.variantId(),i.error())).toList());}
    private void writable(){if(recovery)throw problem(HttpStatus.SERVICE_UNAVAILABLE,"RECOVERY_MODE_READ_ONLY","api.recovery-mode-write-guard.restore-verification-mode");}
    private static ApiProblemException limit(){return problem(HttpStatus.CONFLICT,"REFERENCE_CATALOG_CACHE_JOB_LIMIT","api.reference-catalog.cacheJobLimit");}
}
