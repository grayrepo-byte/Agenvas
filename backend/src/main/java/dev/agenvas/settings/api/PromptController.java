package dev.agenvas.settings.api;

import dev.agenvas.settings.application.PromptService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/settings/prompts")
public class PromptController {
    private final PromptService prompts;
    public PromptController(PromptService prompts) { this.prompts = prompts; }
    public record PromptList(List<PromptService.Prompt> items) {}
    @GetMapping public ResponseEntity<PromptList> list() { return response(new PromptList(prompts.list())); }
    @GetMapping("/{id}") public ResponseEntity<PromptService.Prompt> get(@PathVariable UUID id) { return response(prompts.get(id)); }
    @PostMapping public ResponseEntity<PromptService.Prompt> create(@Valid @RequestBody Create request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
                prompts.create(request.key(), request.kind(), request.name(), request.description(), request.content()));
    }
    @PutMapping("/{id}") public ResponseEntity<PromptService.Prompt> update(@PathVariable UUID id, @Valid @RequestBody Update request) {
        return response(prompts.update(id, request.expectedVersion(), request.name(), request.description(), request.content()));
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam @Positive long expectedVersion) {
        prompts.delete(id, expectedVersion); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    public record Create(@NotBlank @Pattern(regexp = PromptService.KEY_PATTERN) String key, @NotNull PromptService.Kind kind,
            @NotBlank @Size(max = PromptService.MAX_NAME_LENGTH) String name,
            @Size(max = PromptService.MAX_DESCRIPTION_LENGTH) String description,
            @NotBlank @Size(max = PromptService.MAX_CONTENT_LENGTH) String content) {}
    public record Update(@Positive long expectedVersion, @NotBlank @Size(max = PromptService.MAX_NAME_LENGTH) String name,
            @Size(max = PromptService.MAX_DESCRIPTION_LENGTH) String description,
            @NotBlank @Size(max = PromptService.MAX_CONTENT_LENGTH) String content) {}
}
