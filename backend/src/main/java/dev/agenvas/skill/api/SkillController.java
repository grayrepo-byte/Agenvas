package dev.agenvas.skill.api;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.skill.application.SkillFormat;
import dev.agenvas.skill.application.SkillService;
import dev.agenvas.skill.domain.SkillContent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Public DTOs contain authenticated content URLs, never archived keys or source file paths. */
@RestController
@RequestMapping("/api/v1/skills")
public final class SkillController {
    private static final int MAX_CURSOR_LENGTH = 1000;
    private final SkillService skills;
    private final ApiMessages messages;
    public SkillController(SkillService skills, ApiMessages messages) { this.skills = skills; this.messages = messages; }
    @GetMapping public SkillService.Page list(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam(defaultValue = "") @Size(max = SkillFormat.MAX_TITLE_LENGTH) String query,
            @RequestParam(defaultValue = "false") boolean trash,
            @RequestParam(required = false) @Size(max = MAX_CURSOR_LENGTH) String cursor) {
        return skills.list(principal.userId(), query, trash, cursor);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public SkillService.SkillResponse create(@AuthenticationPrincipal AdminPrincipal principal, @Valid @RequestBody CreateRequest request) {
        return skills.create(principal.userId(), request.title(), request.description() == null ? "" : request.description());
    }
    @GetMapping("/{skillId}") public SkillService.SkillResponse get(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID skillId) {
        return skills.get(principal.userId(), skillId);
    }
    @PatchMapping("/{skillId}") public SkillService.SkillResponse update(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID skillId, @Valid @RequestBody MetadataRequest request) {
        return skills.updateMetadata(principal.userId(), skillId, request.expectedVersion(), request.title(), request.description(), request.trashed());
    }
    @GetMapping("/{skillId}/draft") public SkillService.DraftResponse draft(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID skillId) {
        return skills.getDraft(principal.userId(), skillId);
    }
    @PutMapping("/{skillId}/draft") public SkillService.DraftResponse save(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID skillId, @Valid @RequestBody DraftRequest request) {
        return skills.saveDraft(principal.userId(), skillId, request.expectedVersion(),
                new SkillContent.DraftContent(SkillContent.SCHEMA_VERSION, request.skillMd(), request.outputKinds(), request.inputSlots(), request.resources(), request.assets()));
    }
    @GetMapping("/{skillId}/versions") public List<SkillService.VersionSummary> versions(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID skillId) {
        return skills.versions(principal.userId(), skillId);
    }
    @GetMapping("/{skillId}/versions/{versionId}") public SkillService.VersionResponse version(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID skillId, @PathVariable UUID versionId) { return skills.getVersion(principal.userId(), skillId, versionId); }
    @PostMapping("/{skillId}/versions") @ResponseStatus(HttpStatus.ACCEPTED)
    public SkillService.OperationResponse publish(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID skillId,
            @Valid @RequestBody PublishRequest request, HttpServletRequest http) {
        return localized(skills.publish(principal.userId(), skillId, request.expectedDraftVersion(), request.commandKey()), http);
    }
    @GetMapping("/operations/{operationId}") public SkillService.OperationResponse operation(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID operationId, HttpServletRequest http) { return localized(skills.getOperation(principal.userId(), operationId), http); }
    @PostMapping("/operations/{operationId}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public SkillService.OperationResponse retry(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID operationId, HttpServletRequest http) {
        return localized(skills.retry(principal.userId(), operationId), http);
    }
    @PostMapping("/{skillId}/copy") @ResponseStatus(HttpStatus.CREATED)
    public SkillService.SkillResponse copy(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID skillId,
            @Valid @RequestBody CopyRequest request) { return skills.copy(principal.userId(), skillId, request.skillVersionId(), request.title()); }
    @PostMapping("/{skillId}/versions/{versionId}/copy") public SkillService.DraftResponse copyToDraft(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID skillId, @PathVariable UUID versionId, @Valid @RequestBody CopyDraftRequest request) {
        return skills.copyVersionToDraft(principal.userId(), skillId, versionId, request.expectedDraftVersion());
    }
    @RequestMapping(value = "/{skillId}/versions/{versionId}/assets/{alias}/file", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<org.springframework.core.io.Resource> file(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID skillId, @PathVariable UUID versionId, @PathVariable String alias) { return stream(skills.file(principal.userId(), skillId, versionId, alias, false)); }
    @GetMapping("/{skillId}/versions/{versionId}/assets/{alias}/thumbnail")
    public ResponseEntity<org.springframework.core.io.Resource> thumbnail(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID skillId, @PathVariable UUID versionId, @PathVariable String alias) { return stream(skills.file(principal.userId(), skillId, versionId, alias, true)); }
    private ResponseEntity<org.springframework.core.io.Resource> stream(dev.agenvas.library.application.LibraryService.MediaFile file) {
        var resource = new FileSystemResource(file.path()) {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException { return java.nio.file.Files.newInputStream(file.path(), java.nio.file.LinkOption.NOFOLLOW_LINKS); }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType())).contentLength(file.size())
                .header("Cache-Control", "private, no-store").header("Accept-Ranges", "bytes").header("X-Content-Type-Options", "nosniff").body(resource);
    }
    private SkillService.OperationResponse localized(SkillService.OperationResponse response, HttpServletRequest request) {
        return new SkillService.OperationResponse(response.id(), response.skillId(), response.status(), response.resultVersionId(),
                response.errorCode(), messages.persisted(response.errorDetail(), request));
    }
    public record CreateRequest(@NotBlank @Size(max = SkillFormat.MAX_TITLE_LENGTH) String title, @Size(max = SkillFormat.MAX_DESCRIPTION_LENGTH) String description) {}
    public record MetadataRequest(@NotNull @PositiveOrZero Long expectedVersion, @NotBlank @Size(max = SkillFormat.MAX_TITLE_LENGTH) String title,
            @NotNull @Size(max = SkillFormat.MAX_DESCRIPTION_LENGTH) String description, @NotNull Boolean trashed) {}
    public record DraftRequest(@NotNull @PositiveOrZero Long expectedVersion, @NotNull @Size(max = SkillFormat.MAX_SKILL_LENGTH) String skillMd,
            @NotNull List<Artifact.Kind> outputKinds, @NotNull List<SkillContent.InputSlot> inputSlots,
            @NotNull List<SkillContent.Resource> resources, @NotNull List<SkillContent.DraftAsset> assets) {}
    public record PublishRequest(@NotNull @PositiveOrZero Long expectedDraftVersion, @NotBlank @Size(max = SkillFormat.MAX_KEY_LENGTH) String commandKey) {}
    public record CopyRequest(@NotBlank @Size(max = SkillFormat.MAX_TITLE_LENGTH) String title, @NotNull UUID skillVersionId) {}
    public record CopyDraftRequest(@NotNull @PositiveOrZero Long expectedDraftVersion) {}
}
