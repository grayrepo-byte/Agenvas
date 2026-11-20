package dev.agenvas.task.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.task.application.ImageStudioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/** Session-authenticated Studio API: requests never accept node IDs, project selection or remote reference URLs. */
@RestController
@RequestMapping("/api/v1/image-studio")
public class ImageStudioController {
    private final ImageStudioService studio;

    public ImageStudioController(ImageStudioService studio) {
        this.studio = studio;
    }

    @GetMapping("/context")
    public ResponseEntity<ImageStudioService.Context> context(@AuthenticationPrincipal AdminPrincipal principal) {
        return privateResponse(studio.context(principal.userId()));
    }

    @PostMapping(path = "/references", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageStudioService.Reference> uploadReference(
            @AuthenticationPrincipal AdminPrincipal principal, @RequestPart("file") MultipartFile file)
            throws IOException {
        try (var input = file.getInputStream()) {
            return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                    .body(studio.uploadReference(principal.userId(), input));
        }
    }

    @PostMapping("/batches")
    public ResponseEntity<ImageStudioService.BatchView> accept(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestHeader("Idempotency-Key") String commandKey, @Valid @RequestBody CreateBatch request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(studio.accept(principal.userId(), request.capabilityId(),
                        request.expectedCapabilityVersion(), request.prompt(), request.parameters(),
                        request.referenceVersionIds(), commandKey));
    }

    @GetMapping("/batches")
    public ResponseEntity<ImageStudioService.BatchPage> list(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam(required = false) String before, @RequestParam(required = false) Integer limit) {
        return privateResponse(studio.list(principal.userId(), before, limit));
    }

    @GetMapping("/batches/{batchId}")
    public ResponseEntity<ImageStudioService.BatchView> get(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID batchId) {
        return privateResponse(studio.get(principal.userId(), batchId));
    }

    public record CreateBatch(@NotNull UUID capabilityId, @NotNull @Min(1) Integer expectedCapabilityVersion,
            @NotBlank @Size(max = 8000) String prompt, @NotNull JsonNode parameters,
            @NotNull @Size(max = 14) List<@NotNull UUID> referenceVersionIds) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown image Studio request field");
        }
    }

    private static <T> ResponseEntity<T> privateResponse(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
