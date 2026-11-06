package dev.agenvas.referencecatalog.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.referencecatalog.application.ReferenceCatalogCacheService;
import dev.agenvas.referencecatalog.infrastructure.ReferenceCatalogImageCache;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Global shared cache management is administrator-only, session/CSRF protected by settings matchers. */
@RestController
@RequestMapping("/api/v1/settings/reference-catalog/cache")
public class ReferenceCatalogCacheController {
    private final ReferenceCatalogCacheService cache;
    public ReferenceCatalogCacheController(ReferenceCatalogCacheService cache){this.cache=cache;}
    public record Create(@NotBlank @Size(max=100) String sourceId,@NotNull UUID snapshotId,@NotNull @Size(max=5000) List<@NotBlank @Size(max=100) String> entryIds,@NotBlank @Size(max=200) String commandKey){}
    public record Clear(@Size(max=100) String sourceId,UUID snapshotId,@Size(max=5000) List<@NotBlank @Size(max=100) String> entryIds){}
    public record Jobs(List<ReferenceCatalogCacheService.Job> items){}
    @GetMapping public ReferenceCatalogImageCache.Stats stats(){return cache.stats();}
    @GetMapping("/jobs") public Jobs jobs(){return new Jobs(cache.list());}
    @GetMapping("/jobs/{jobId}") public ReferenceCatalogCacheService.Job job(@PathVariable UUID jobId){return cache.get(jobId);}
    @PostMapping("/jobs") @ResponseStatus(HttpStatus.ACCEPTED) public ReferenceCatalogCacheService.Job create(@AuthenticationPrincipal AdminPrincipal principal,@Valid @RequestBody Create input){return cache.create(principal.userId(),input.sourceId(),input.snapshotId(),input.entryIds(),input.commandKey());}
    @PostMapping("/jobs/{jobId}/retry") public ReferenceCatalogCacheService.Job retry(@PathVariable UUID jobId){return cache.retry(jobId);}
    @PostMapping("/jobs/{jobId}/cancel") public ReferenceCatalogCacheService.Job cancel(@PathVariable UUID jobId){return cache.cancel(jobId);}
    @PostMapping("/clear") public ReferenceCatalogImageCache.Removed clear(@Valid @RequestBody Clear input){return cache.clear(input.sourceId(),input.snapshotId(),input.entryIds()==null?List.of():input.entryIds());}
}
