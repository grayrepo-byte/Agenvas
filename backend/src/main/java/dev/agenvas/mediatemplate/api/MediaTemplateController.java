package dev.agenvas.mediatemplate.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.mediatemplate.application.MediaTemplateService;
import dev.agenvas.mediatemplate.domain.MediaTemplate.Scope;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Authenticated prompt presets. System mutations use the administrator settings boundary. */
@RestController
@RequestMapping("/api/v1")
public class MediaTemplateController {
    private final MediaTemplateService templates;
    public MediaTemplateController(MediaTemplateService templates) { this.templates = templates; }

    @GetMapping("/media-templates")
    public MediaTemplateService.Page list(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam(required = false) TargetKind targetKind, @RequestParam(required = false) Scope scope,
            @RequestParam(defaultValue = "") @Size(max = MediaTemplateService.MAX_NAME_LENGTH) String query) {
        return templates.list(principal.userId(), targetKind, scope, query);
    }
    @GetMapping("/media-templates/{templateId}")
    public MediaTemplateService.TemplateResponse get(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID templateId) {
        return templates.get(principal.userId(), templateId);
    }
    @PostMapping("/media-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public MediaTemplateService.TemplateResponse create(@AuthenticationPrincipal AdminPrincipal principal, @Valid @RequestBody CreateRequest request) {
        return create(principal, Scope.PERSONAL, request);
    }
    @PostMapping("/settings/media-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public MediaTemplateService.TemplateResponse createSystem(@AuthenticationPrincipal AdminPrincipal principal, @Valid @RequestBody CreateRequest request) {
        return create(principal, Scope.SYSTEM, request);
    }
    private MediaTemplateService.TemplateResponse create(AdminPrincipal principal, Scope scope, CreateRequest request) {
        return templates.create(principal.userId(), scope, request.name(), request.targetKind(), request.prompt(), request.imageIds());
    }
    @PatchMapping("/media-templates/{templateId}")
    public MediaTemplateService.TemplateResponse update(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID templateId,
            @Valid @RequestBody UpdateRequest request) { return update(principal, Scope.PERSONAL, templateId, request); }
    @PatchMapping("/settings/media-templates/{templateId}")
    public MediaTemplateService.TemplateResponse updateSystem(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID templateId,
            @Valid @RequestBody UpdateRequest request) { return update(principal, Scope.SYSTEM, templateId, request); }
    private MediaTemplateService.TemplateResponse update(AdminPrincipal principal, Scope scope, UUID id, UpdateRequest request) {
        return templates.update(principal.userId(), scope, id, request.expectedVersion(), request.name(), request.targetKind(), request.prompt(), request.imageIds());
    }
    @DeleteMapping("/media-templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID templateId, @RequestParam @PositiveOrZero long expectedVersion) {
        templates.delete(principal.userId(), Scope.PERSONAL, templateId, expectedVersion);
    }
    @DeleteMapping("/settings/media-templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSystem(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID templateId, @RequestParam @PositiveOrZero long expectedVersion) {
        templates.delete(principal.userId(), Scope.SYSTEM, templateId, expectedVersion);
    }
    @PostMapping(value = "/media-templates/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MediaTemplateService.ImageResponse upload(@AuthenticationPrincipal AdminPrincipal principal, @RequestPart MultipartFile file) {
        return templates.upload(principal.userId(), file);
    }
    @PostMapping("/media-templates/images/from-version")
    @ResponseStatus(HttpStatus.CREATED)
    public MediaTemplateService.ImageResponse copy(@AuthenticationPrincipal AdminPrincipal principal, @Valid @RequestBody CopyRequest request) {
        return templates.copyImage(principal.userId(), request.projectId(), request.versionId());
    }
    @DeleteMapping("/media-templates/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID imageId) {
        templates.deleteImage(principal.userId(), imageId);
    }
    @RequestMapping(value = "/media-templates/images/{imageId}/content", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Resource> content(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID imageId) {
        return stream(templates.file(principal.userId(), imageId, false));
    }
    @GetMapping("/media-templates/images/{imageId}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID imageId) {
        return stream(templates.file(principal.userId(), imageId, true));
    }
    @PostMapping("/projects/{projectId}/media-templates/{templateId}/import")
    public MediaTemplateService.ImportResponse importTemplate(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID templateId, @Valid @RequestBody ImportRequest request) {
        return templates.importTemplate(principal.userId(), projectId, templateId, request.expectedTemplateVersion(), request.commandKey());
    }
    private ResponseEntity<Resource> stream(MediaTemplateService.MediaFile file) {
        Resource resource = new org.springframework.core.io.FileSystemResource(file.path()) {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                return java.nio.file.Files.newInputStream(file.path(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
            }
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType())).contentLength(file.size()).body(resource);
    }
    public record CreateRequest(@NotBlank @Size(max = MediaTemplateService.MAX_NAME_LENGTH) String name,
            @NotNull TargetKind targetKind, @NotBlank @Size(max = MediaTemplateService.MAX_PROMPT_LENGTH) String prompt,
            @NotNull @Size(max = MediaTemplateService.MAX_IMAGES) List<@NotNull UUID> imageIds) {}
    public record UpdateRequest(@NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = MediaTemplateService.MAX_NAME_LENGTH) String name,
            @NotNull TargetKind targetKind, @NotBlank @Size(max = MediaTemplateService.MAX_PROMPT_LENGTH) String prompt,
            @NotNull @Size(max = MediaTemplateService.MAX_IMAGES) List<@NotNull UUID> imageIds) {}
    public record CopyRequest(@NotNull UUID projectId, @NotNull UUID versionId) {}
    public record ImportRequest(@NotNull @PositiveOrZero Long expectedTemplateVersion,
            @NotBlank @Size(max = MediaTemplateService.MAX_COMMAND_KEY_LENGTH) String commandKey) {}
}
