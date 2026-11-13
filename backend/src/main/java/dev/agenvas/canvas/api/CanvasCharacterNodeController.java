package dev.agenvas.canvas.api;

import static dev.agenvas.canvas.application.CanvasCharacterNodeModels.*;

import dev.agenvas.canvas.application.CanvasCharacterNodeService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.character.application.CharacterModels.CharacterBindingApplied;
import dev.agenvas.identity.application.AdminPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Authenticated fixed character cards, logical edges and explicit application. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class CanvasCharacterNodeController {
    private final CanvasCharacterNodeService nodes;

    public CanvasCharacterNodeController(CanvasCharacterNodeService nodes) {
        this.nodes = nodes;
    }

    @PutMapping("/canvas/character-nodes/{itemId}")
    public CanvasService.CanvasEntry create(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID itemId,
            @RequestBody CreateNode request) {
        return nodes.create(principal.userId(), projectId, itemId, request);
    }

    @PutMapping("/canvas/character-nodes/{itemId}/selection")
    public CharacterNode update(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID itemId,
            @RequestBody UpdateSelection request) {
        return nodes.update(principal.userId(), projectId, itemId, request);
    }

    public record Connections(List<CharacterConnection> items) {
    }

    @GetMapping("/canvas/character-connections")
    public Connections connections(
            @AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId) {
        return new Connections(nodes.connections(principal.userId(), projectId));
    }

    @GetMapping("/canvas/character-connections/{connectionId}/receipt")
    public CharacterConnection connectionReceipt(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID connectionId) {
        return nodes.connectionReceipt(principal.userId(), projectId, connectionId);
    }

    @GetMapping("/canvas-items/{targetId}/character-node-bindings/receipts/{commandId}")
    public CharacterBindingApplied applicationReceipt(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID targetId,
            @PathVariable UUID commandId) {
        return nodes.applicationReceipt(principal.userId(), projectId, targetId, commandId);
    }

    @PostMapping("/canvas/character-connections")
    @ResponseStatus(HttpStatus.CREATED)
    public CharacterConnection connect(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestBody Connect request) {
        return nodes.connect(principal.userId(), projectId, request);
    }

    @PostMapping("/canvas/character-connections/{connectionId}/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID connectionId,
            @RequestBody Disconnect request) {
        nodes.disconnect(principal.userId(), projectId, connectionId, request);
    }

    @PostMapping("/canvas-items/{targetId}/character-node-bindings/preview")
    public NodeBindingPreview preview(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID targetId,
            @RequestBody Preview request) {
        return nodes.preview(principal.userId(), projectId, targetId, request);
    }

    @PostMapping("/canvas-items/{targetId}/character-node-bindings/apply")
    public CharacterBindingApplied apply(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID targetId,
            @RequestBody Apply request) {
        return nodes.apply(principal.userId(), projectId, targetId, request);
    }
}
