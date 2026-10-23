package dev.agenvas.character.api;

import static dev.agenvas.character.application.CharacterModels.*;

import dev.agenvas.character.application.CharacterService;
import dev.agenvas.identity.application.AdminPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated project character editing, explicit draft application and independent review. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class CharacterController {
    private final CharacterService characters;
    public CharacterController(CharacterService characters) { this.characters = characters; }
    @GetMapping("/characters")
    public CharacterIndex list(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId) {
        return characters.list(principal.userId(), projectId);
    }
    @PutMapping("/characters/{characterId}")
    public CharacterProfile save(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID characterId, @RequestBody SaveCharacter request) {
        return characters.save(principal.userId(), projectId, characterId, request);
    }
    @PostMapping("/characters/{characterId}/preview-change")
    public CharacterChangePreview previewChange(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID characterId, @RequestBody SaveCharacter request) {
        return characters.previewChange(principal.userId(), projectId, characterId, request);
    }
    @PostMapping("/characters/{characterId}/archive")
    public CharacterProfile archive(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID characterId, @RequestBody ArchiveCharacter request) {
        return characters.archive(principal.userId(), projectId, characterId, request);
    }
    @PostMapping("/canvas-items/{canvasItemId}/character-bindings/preview")
    public CharacterBindingPreview preview(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID canvasItemId, @RequestBody PreviewCharacterBindings request) {
        return characters.preview(principal.userId(), projectId, canvasItemId, request);
    }
    @PostMapping("/canvas-items/{canvasItemId}/character-bindings/apply")
    public CharacterBindingApplied apply(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID canvasItemId, @RequestBody ApplyCharacterBindings request) {
        return characters.apply(principal.userId(), projectId, canvasItemId, request);
    }
    @PostMapping("/canvas-items/{canvasItemId}/character-bindings/review")
    public CharacterBinding review(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID canvasItemId, @RequestBody ReviewCharacterBindings request) {
        return characters.review(principal.userId(), projectId, canvasItemId, request);
    }
}
