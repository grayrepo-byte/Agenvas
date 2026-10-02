package dev.agenvas.library.api;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.library.domain.LibraryCommand;
import dev.agenvas.library.domain.LibraryEntry;
import dev.agenvas.shared.i18n.ApiMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Public account-scoped DTOs never expose archived object keys, pinned paths or transfer inputs. */
@RestController
@RequestMapping("/api/v1")
public class LibraryController {
    private static final int MAX_CURSOR_LENGTH = 1000;
    private static final String MIN_CANVAS_COORDINATE = "-1000000";
    private static final String MAX_CANVAS_COORDINATE = "1000000";
    private final LibraryService library;
    private final ApiMessages messages;
    public LibraryController(LibraryService library, ApiMessages messages) {
        this.library = library;
        this.messages = messages;
    }
    @GetMapping("/library/entries")
    public LibraryService.Page list(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam(required = false) LibraryEntry.Category category,
            @RequestParam(required = false) Artifact.Kind kind,
            @RequestParam(defaultValue = "") @Size(max = LibraryService.MAX_NAME_LENGTH) String query,
            @RequestParam(defaultValue = "false") boolean favorite,
            @RequestParam(defaultValue = "false") boolean trash,
            @RequestParam(defaultValue = "SAVED") LibraryEntry.Sort sort,
            @RequestParam(required = false) @Size(max = MAX_CURSOR_LENGTH) String cursor) {
        return library.list(principal.userId(), category, kind, query, favorite, trash, sort, cursor);
    }
    @GetMapping("/library/entries/{entryId}")
    public LibraryService.EntryResponse get(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID entryId) {
        return library.get(principal.userId(), entryId);
    }
    @GetMapping("/projects/{projectId}/canvas-items/{itemId}/library-saves")
    public LibraryService.SourceContext source(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID itemId) {
        return library.source(principal.userId(), projectId, itemId);
    }
    @PostMapping("/projects/{projectId}/canvas-items/{itemId}/library-saves")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResponse save(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID itemId, @Valid @RequestBody SaveRequest request, HttpServletRequest httpRequest) {
        return response(library.save(principal.userId(), projectId, itemId, request.versionId(),
                request.expectedSelectionEpoch(), request.expectedArtifactVersion(), request.name(), request.category(), request.commandKey()), httpRequest);
    }
    @GetMapping("/library/commands/{commandId}")
    public CommandResponse command(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID commandId, HttpServletRequest httpRequest) {
        return response(library.command(principal.userId(), commandId), httpRequest);
    }
    @PostMapping("/library/commands/{commandId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResponse retry(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID commandId, HttpServletRequest httpRequest) {
        return response(library.retry(principal.userId(), commandId), httpRequest);
    }
    @PatchMapping("/library/entries/{entryId}")
    public LibraryService.EntryResponse update(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID entryId,
            @Valid @RequestBody UpdateRequest request) {
        return library.update(principal.userId(), entryId, request.expectedVersion(), request.name(), request.category(), request.favorite());
    }
    @PostMapping("/library/entries/{entryId}/trash")
    public LibraryService.EntryResponse trash(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID entryId,
            @Valid @RequestBody VersionRequest request) {
        return library.trash(principal.userId(), entryId, request.expectedVersion(), false);
    }
    @PostMapping("/library/entries/{entryId}/restore")
    public LibraryService.EntryResponse restore(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID entryId,
            @Valid @RequestBody VersionRequest request) {
        return library.trash(principal.userId(), entryId, request.expectedVersion(), true);
    }
    @DeleteMapping("/library/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID entryId,
            @RequestParam @PositiveOrZero long expectedVersion) {
        library.delete(principal.userId(), entryId, expectedVersion);
    }
    public record UpdateRequest(@PositiveOrZero long expectedVersion,
            @NotBlank @Size(max = LibraryService.MAX_NAME_LENGTH) String name,
            @NotNull LibraryEntry.Category category, boolean favorite) {}
    public record VersionRequest(@PositiveOrZero long expectedVersion) {}

    @RequestMapping(value = "/library/entries/{entryId}/content", method = {RequestMethod.GET, RequestMethod.HEAD})
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> content(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID entryId) {
        return stream(library.file(principal.userId(), entryId, false));
    }
    @GetMapping("/library/entries/{entryId}/thumbnail")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> thumbnail(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID entryId) {
        return stream(library.file(principal.userId(), entryId, true));
    }
    private org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> stream(LibraryService.MediaFile file) {
        var resource = new org.springframework.core.io.FileSystemResource(file.path()) {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                return java.nio.file.Files.newInputStream(file.path(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
            }
        };
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size()).header("Cache-Control", "private, no-store")
                .header("Accept-Ranges", "bytes").header("X-Content-Type-Options", "nosniff").body(resource);
    }
    @PostMapping(value = "/library/uploads", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResponse upload(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam @NotBlank @Size(max = LibraryService.MAX_NAME_LENGTH) String name,
            @RequestParam LibraryEntry.Category category, @RequestParam dev.agenvas.asset.domain.Asset.MediaKind kind,
            @RequestParam @NotBlank @Size(max = LibraryService.MAX_COMMAND_KEY_LENGTH) String commandKey,
            @RequestParam org.springframework.web.multipart.MultipartFile file, HttpServletRequest httpRequest) {
        return response(library.upload(principal.userId(), name, category, kind, commandKey, file), httpRequest);
    }

    @PostMapping("/projects/{projectId}/library-imports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResponse importEntry(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @Valid @RequestBody ImportRequest request, HttpServletRequest httpRequest) {
        return response(library.importEntry(principal.userId(), projectId, request.entryId(), request.expectedVersion(),
                request.x(), request.y(), request.commandKey()), httpRequest);
    }
    @PostMapping("/projects/{projectId}/canvas-items/{itemId}/library-references")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResponse reference(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID itemId, @Valid @RequestBody ReferenceRequest request, HttpServletRequest httpRequest) {
        var draft = request.draft();
        return response(library.reference(principal.userId(), projectId, itemId, request.entryId(), request.expectedVersion(),
                new LibraryService.ReferenceDraft(draft.expectedVersion(), draft.prompt(), draft.parameters(), draft.durationSeconds(),
                        draft.capabilityId(), draft.videoInputMode(), draft.mediaInputs(), draft.mentions(), draft.styleId()),
                request.role(), request.color(), request.commandKey()), httpRequest);
    }
    public record ReferenceDraftRequest(@PositiveOrZero long expectedVersion,
            @NotNull @Size(max = LibraryService.MAX_DRAFT_PROMPT_LENGTH) String prompt, JsonNode parameters, Integer durationSeconds, UUID capabilityId,
            dev.agenvas.artifact.domain.MediaDraft.VideoInputMode videoInputMode,
            java.util.List<dev.agenvas.artifact.application.MediaDraftService.SaveMediaInput> mediaInputs,
            java.util.List<dev.agenvas.artifact.domain.MediaDraft.PromptMention> mentions, UUID styleId) {}

    public record ReferenceRequest(@NotNull UUID entryId, @PositiveOrZero long expectedVersion,
            @NotNull @Valid ReferenceDraftRequest draft,
            @NotNull dev.agenvas.artifact.domain.MediaDraft.InputRole role,
            @NotNull @jakarta.validation.constraints.Pattern(regexp = "^#[0-9A-F]{6}$") String color,
            @NotBlank @Size(max = LibraryService.MAX_COMMAND_KEY_LENGTH) String commandKey) {}

    public record ImportRequest(@NotNull UUID entryId, @PositiveOrZero long expectedVersion,
            @NotNull @jakarta.validation.constraints.DecimalMin(MIN_CANVAS_COORDINATE) @jakarta.validation.constraints.DecimalMax(MAX_CANVAS_COORDINATE) java.math.BigDecimal x,
            @NotNull @jakarta.validation.constraints.DecimalMin(MIN_CANVAS_COORDINATE) @jakarta.validation.constraints.DecimalMax(MAX_CANVAS_COORDINATE) java.math.BigDecimal y,
            @NotBlank @Size(max = LibraryService.MAX_COMMAND_KEY_LENGTH) String commandKey) {}

    public record SaveRequest(@NotNull UUID versionId, @PositiveOrZero long expectedSelectionEpoch,
            @PositiveOrZero Long expectedArtifactVersion, @NotBlank @Size(max = LibraryService.MAX_NAME_LENGTH) String name,
            @NotNull LibraryEntry.Category category, @NotBlank @Size(max = LibraryService.MAX_COMMAND_KEY_LENGTH) String commandKey) {}
    public record CommandResponse(UUID id, LibraryCommand.Status status, JsonNode result, String errorCode, String errorDetail) {}

    private CommandResponse response(LibraryCommand command, HttpServletRequest request) {
        return new CommandResponse(command.id(), command.status(), command.result(), command.errorCode(),
                messages.persisted(command.errorDetail(), request));
    }
}
