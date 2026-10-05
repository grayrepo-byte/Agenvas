package dev.agenvas.settings.api;

import dev.agenvas.settings.application.MediaStyleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Authenticated previews and administrator-only global settings. */
@RestController
public class MediaStyleController {
    private final MediaStyleService styles;
    public MediaStyleController(MediaStyleService styles) { this.styles = styles; }

    @GetMapping("/api/v1/media-styles")
    public ResponseEntity<List<MediaStyleService.Summary>> catalog() { return noStore(styles.catalog()); }
    @GetMapping("/api/v1/settings/media-styles")
    public ResponseEntity<List<MediaStyleService.Style>> settings() { return noStore(styles.settings()); }
    @PostMapping("/api/v1/settings/media-styles")
    public ResponseEntity<MediaStyleService.Style> create(@Valid @RequestBody Create request) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
                styles.create(request.name(), request.category(), request.promptSuffix(), request.enabled()));
    }
    @PutMapping("/api/v1/settings/media-styles/{id}")
    public ResponseEntity<MediaStyleService.Style> update(@PathVariable UUID id, @Valid @RequestBody Update request) {
        return noStore(styles.update(id, request.expectedVersion(), request.name(), request.category(),
                request.promptSuffix(), request.enabled()));
    }
    @PostMapping(path = "/api/v1/settings/media-styles/{id}/thumbnail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaStyleService.Style> upload(@PathVariable UUID id,
            @RequestParam @PositiveOrZero long expectedVersion, @RequestPart("file") MultipartFile file) throws IOException {
        try (var input = file.getInputStream()) {
            byte[] png = styles.prepareThumbnail(input);
            return noStore(styles.replaceThumbnail(id, expectedVersion, png));
        }
    }
    @GetMapping("/api/v1/media-styles/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable UUID id) {
        var thumbnail = styles.thumbnail(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(thumbnail.contentType())).body(thumbnail.bytes());
    }
    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
    public record Create(@NotBlank @Size(max = MediaStyleService.MAX_NAME_LENGTH) String name, @NotBlank @Size(max = MediaStyleService.MAX_CATEGORY_LENGTH) String category,
            @NotBlank @Size(max = MediaStyleService.MAX_PROMPT_LENGTH) String promptSuffix, boolean enabled) {}
    public record Update(@PositiveOrZero long expectedVersion, @NotBlank @Size(max = MediaStyleService.MAX_NAME_LENGTH) String name,
            @NotBlank @Size(max = MediaStyleService.MAX_CATEGORY_LENGTH) String category, @NotBlank @Size(max = MediaStyleService.MAX_PROMPT_LENGTH) String promptSuffix, boolean enabled) {}
}
