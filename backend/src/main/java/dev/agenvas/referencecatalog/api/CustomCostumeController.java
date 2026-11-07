package dev.agenvas.referencecatalog.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.referencecatalog.application.CustomCostumeService;
import dev.agenvas.referencecatalog.application.ReferenceCatalogImportService;
import dev.agenvas.referencecatalog.application.ReferenceCatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Only public catalog DTOs cross this boundary; archive paths and transfer snapshots remain private. */
@RestController
@RequestMapping("/api/v1")
public class CustomCostumeController {
    private final CustomCostumeService costumes;
    private final ReferenceCatalogImportService imports;
    public CustomCostumeController(CustomCostumeService costumes,ReferenceCatalogImportService imports){this.costumes=costumes;this.imports=imports;}
    public record Save(@NotNull UUID canvasItemId,@NotNull UUID sourceVersionId,@NotNull @PositiveOrZero Long expectedCanvasItemVersion,
            @NotBlank @Size(max=160) String name,@Size(max=100) String category,@NotNull @Size(max=32) List<@NotBlank @Size(max=64) String> tags,@NotBlank @Size(max=200) String commandKey){}
    public record Update(@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Size(max=160) String name,@Size(max=100) String category,@NotNull @Size(max=32) List<@NotBlank @Size(max=64) String> tags){}
    public record Import(@NotBlank @Size(max=200) String commandKey,@NotNull @PositiveOrZero Long expectedVersion,ReferenceCatalogImportService.Placement placement){}
    @PostMapping("/projects/{projectId}/reference-catalog/costumes") @ResponseStatus(HttpStatus.CREATED)
    public ReferenceCatalogService.EntryView save(@AuthenticationPrincipal AdminPrincipal principal,@PathVariable UUID projectId,@Valid @RequestBody Save input){return costumes.save(principal.userId(),projectId,new CustomCostumeService.Save(input.canvasItemId(),input.sourceVersionId(),input.expectedCanvasItemVersion(),input.name(),input.category(),input.tags(),input.commandKey()));}
    @PatchMapping("/reference-catalog/costumes/{costumeId}")
    public ReferenceCatalogService.EntryView update(@PathVariable("costumeId") UUID id,@Valid @RequestBody Update input){return costumes.update(id,input.expectedVersion(),input.name(),input.category(),input.tags());}
    @DeleteMapping("/reference-catalog/costumes/{costumeId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("costumeId") UUID id,@RequestParam @PositiveOrZero long expectedVersion){costumes.delete(id,expectedVersion);}
    @GetMapping("/reference-catalog/costumes/{costumeId}")
    public ReferenceCatalogService.EntryView get(@PathVariable("costumeId") UUID id){return costumes.get(id);}
    @GetMapping("/reference-catalog/costumes/{costumeId}/image")
    public ResponseEntity<FileSystemResource> image(@PathVariable("costumeId") UUID id){var file=costumes.image(id);return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(file.contentType())).contentLength(file.byteSize()).body(new FileSystemResource(file.path()));}
    @PostMapping("/projects/{projectId}/reference-catalog/costumes/{costumeId}/import")
    public ReferenceCatalogImportService.ImportResponse importImage(@AuthenticationPrincipal AdminPrincipal principal,@PathVariable UUID projectId,@PathVariable("costumeId") UUID id,@Valid @RequestBody Import input){return imports.importCostume(principal.userId(),projectId,id,input.expectedVersion(),input.commandKey(),input.placement());}
}
