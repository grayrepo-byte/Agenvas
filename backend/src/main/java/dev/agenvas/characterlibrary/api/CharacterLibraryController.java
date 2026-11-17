package dev.agenvas.characterlibrary.api;

import static dev.agenvas.characterlibrary.application.CharacterLibraryModels.*;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.characterlibrary.application.CharacterLibraryService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.library.application.LibraryService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Authenticated account commands; public DTOs never contain private object keys or pin paths. */
@RestController
@RequestMapping("/api/v1")
public class CharacterLibraryController {
    private final CharacterLibraryService service;
    public CharacterLibraryController(CharacterLibraryService service) {
        this.service = service;
    }
    @GetMapping("/character-library/profiles")
    public Page list(@AuthenticationPrincipal AdminPrincipal principal, @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "false") boolean archived, @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "30") int limit) {
        return service.list(principal.userId(), query, archived, cursor, limit);
    }
    @GetMapping("/character-library/profiles/{id}")
    public Profile get(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID id) {
        return service.get(principal.userId(), id);
    }
    @PutMapping("/character-library/profiles/{id}")
    public Profile save(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID id,
            @RequestBody Save request) {
        return service.save(principal.userId(), id, request);
    }
    @PostMapping("/character-library/profiles/{id}/archive")
    public Profile archive(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID id,
            @RequestBody Archive request) {
        return service.archive(principal.userId(), id, request);
    }
    @PostMapping("/character-library/profiles/{id}/from-project")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Command publish(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID id,
            @RequestBody Publish request) {
        return service.publish(principal.userId(), id, request);
    }
    @PostMapping("/character-library/media")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Command media(@AuthenticationPrincipal AdminPrincipal principal, @RequestBody MediaSource request) {
        return service.media(principal.userId(), request);
    }
    @PostMapping(value = "/character-library/media/uploads", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Command upload(@AuthenticationPrincipal AdminPrincipal principal, @RequestParam UUID commandId,
            @RequestParam Asset.MediaKind kind, @RequestParam MultipartFile file) {
        return service.upload(principal.userId(), commandId, kind, file);
    }
    @GetMapping("/character-library/commands/{commandId}")
    public Command command(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID commandId) {
        return service.command(principal.userId(), commandId);
    }
    @PostMapping("/character-library/commands/{commandId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Command retry(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID commandId) {
        return service.retry(principal.userId(), commandId);
    }
    @GetMapping("/character-library/media/{mediaId}/content")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> content(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID mediaId) {
        return stream(service.file(principal.userId(), mediaId, false));
    }
    @GetMapping("/character-library/media/{mediaId}/thumbnail")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> thumbnail(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID mediaId) {
        return stream(service.file(principal.userId(), mediaId, true));
    }
    @PostMapping("/projects/{projectId}/character-library/imports/preview")
    public TransferPreview preview(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @RequestBody Transfer request) {
        return service.preview(principal.userId(), projectId, request);
    }
    @PostMapping("/projects/{projectId}/character-library/imports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Command importProfile(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @RequestBody Import request) {
        return service.importProfile(principal.userId(), projectId, request);
    }
    @GetMapping("/projects/{projectId}/character-library/links")
    public Links links(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId) {
        return service.links(principal.userId(), projectId);
    }
    private org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> stream(
            LibraryService.MediaFile file) {
        var resource = new org.springframework.core.io.FileSystemResource(file.path()) {
            @Override
            public java.io.InputStream getInputStream() throws java.io.IOException {
                return java.nio.file.Files.newInputStream(file.path(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
            }
        };
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size()).header("Cache-Control", "private, no-store")
                .header("Accept-Ranges", "bytes")
                .header("X-Content-Type-Options", "nosniff").body(resource);
    }
}
