package dev.agenvas.referencecatalog.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.referencecatalog.application.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReferenceCatalogController {
    private final ReferenceCatalogService catalog;private final ReferenceCatalogImportService imports;
    public ReferenceCatalogController(ReferenceCatalogService catalog,ReferenceCatalogImportService imports){this.catalog=catalog;this.imports=imports;}
    public record Sources(List<Source> items){}
    @GetMapping("/reference-catalog/sources") public Sources sources(){return new Sources(catalog.sources());}
    @GetMapping("/reference-catalog") public ReferenceCatalogService.Page list(@RequestParam(required=false) Kind kind,@RequestParam(required=false) String sourceId,
            @RequestParam(defaultValue="") String query,@RequestParam(required=false) String category,@RequestParam(required=false) String tag,@RequestParam(required=false) String style,
            @RequestParam(required=false) String collection,@RequestParam(required=false) Boolean extended,@RequestParam(required=false) String angle,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="false") boolean includeSelfCreated){return catalog.list(kind,sourceId,query,category,tag,style,collection,extended,angle,offset,limit,includeSelfCreated);}
    public record Create(@NotBlank @Size(max=100) String id,@NotBlank @Size(max=160) String name,@NotBlank @Size(max=100) String owner,
            @NotBlank @Size(max=100) String repository,@NotBlank @Size(max=160) String ref,@Size(max=512) String manifestPath,@NotNull Format format,boolean imageDistributionApproved,SourceType sourceType){}
    public record Update(@NotNull @PositiveOrZero Long expectedVersion,@NotNull Boolean enabled,@NotNull Boolean imageDistributionApproved){}
    public record Sync(@NotNull @PositiveOrZero Long expectedVersion){}
    @PostMapping("/settings/reference-catalog/sources") @ResponseStatus(HttpStatus.CREATED)
    public Source create(@Valid @RequestBody Create request){return catalog.create(request.id(),request.name(),request.owner(),request.repository(),request.ref(),request.manifestPath(),request.format(),request.imageDistributionApproved(),request.sourceType());}
    @PatchMapping("/settings/reference-catalog/sources/{sourceId}") public Source update(@PathVariable String sourceId,@Valid @RequestBody Update request){return catalog.update(sourceId,request.expectedVersion(),request.enabled(),request.imageDistributionApproved());}
    @PostMapping("/settings/reference-catalog/sources/{sourceId}/sync") public ReferenceCatalogService.SyncResult sync(@PathVariable String sourceId,@Valid @RequestBody Sync request){return catalog.sync(sourceId,request.expectedVersion());}
    @GetMapping("/reference-catalog/sources/{sourceId}/snapshots/{snapshotId}/entries/{entryId}/variants/{variantId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable String sourceId,@PathVariable UUID snapshotId,@PathVariable String entryId,@PathVariable String variantId){
        var selected=catalog.selection(sourceId,snapshotId,entryId,variantId);byte[] bytes=catalog.download(selected);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff")
                .contentType(MediaType.parseMediaType(selected.variant().contentType())).contentLength(bytes.length).body(bytes);
    }
    @PostMapping("/projects/{projectId}/reference-catalog/import") public ReferenceCatalogImportService.ImportResponse importImage(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,@RequestBody ReferenceCatalogImportService.Input input){return imports.importImage(principal.userId(),projectId,input);}
}
