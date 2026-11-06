package dev.agenvas.referencecatalog.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.referencecatalog.application.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReferenceCatalogPrivateImageController {
    private final ReferenceCatalogPrivateImageService images;private final ReferenceCatalogImportService imports;
    public ReferenceCatalogPrivateImageController(ReferenceCatalogPrivateImageService images,ReferenceCatalogImportService imports){this.images=images;this.imports=imports;}
    public record Create(@NotBlank @Size(max=100) String sourceId,@NotNull UUID snapshotId,@NotBlank @Size(max=100) String entryId,@NotBlank @Size(max=100) String variantId,@NotBlank @Size(max=200) String commandKey){}
    public record Import(@NotBlank @Size(max=200) String commandKey,ReferenceCatalogImportService.Placement placement){}
    public record Images(List<ReferenceCatalogPrivateImageService.PrivateImage> items){}
    @GetMapping("/reference-catalog/private-images") public Images list(@AuthenticationPrincipal AdminPrincipal principal,@RequestParam(required=false) String sourceId,@RequestParam(required=false) UUID snapshotId){return new Images(images.list(principal.userId(),sourceId,snapshotId));}
    @PostMapping("/reference-catalog/private-images") public ReferenceCatalogPrivateImageService.PrivateImage create(@AuthenticationPrincipal AdminPrincipal principal,@Valid @RequestBody Create input){return images.acquire(principal.userId(),input.sourceId(),input.snapshotId(),input.entryId(),input.variantId(),input.commandKey());}
    @DeleteMapping("/reference-catalog/private-images/{imageId}") public ReferenceCatalogPrivateImageService.PrivateImage clear(@AuthenticationPrincipal AdminPrincipal principal,@PathVariable UUID imageId){return images.clear(principal.userId(),imageId);}
    @GetMapping("/reference-catalog/private-images/{imageId}/preview") public ResponseEntity<byte[]> preview(@AuthenticationPrincipal AdminPrincipal principal,@PathVariable UUID imageId){byte[] bytes=images.read(principal.userId(),imageId);return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(bytes.length).header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").body(bytes);}
    @PostMapping("/projects/{projectId}/reference-catalog/private-images/{imageId}/import") public ReferenceCatalogImportService.ImportResponse importImage(@AuthenticationPrincipal AdminPrincipal principal,@PathVariable UUID projectId,@PathVariable UUID imageId,@Valid @RequestBody Import input){return imports.importPrivateImage(principal.userId(),projectId,imageId,input.commandKey(),input.placement());}
}
