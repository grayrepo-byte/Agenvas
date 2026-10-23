package dev.agenvas.canvas.api;

import static dev.agenvas.canvas.application.CanvasEditModels.*;
import dev.agenvas.canvas.application.CanvasEditService;
import dev.agenvas.identity.application.AdminPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Presentation groups, atomic content copies and bounded undo/redo receipts. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/canvas")
public class CanvasEditController {
    private final CanvasEditService edits;
    public CanvasEditController(CanvasEditService edits) { this.edits = edits; }
    @GetMapping("/editor")
    public EditorResponse editor(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId) {
        return EditorResponse.from(edits.get(principal.userId(), projectId));
    }
    @PostMapping("/edits")
    public EditorResponse edit(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @RequestBody Edit request) {
        return EditorResponse.from(edits.edit(principal.userId(), projectId, request));
    }
    @PostMapping("/edits/{receiptId}/replay")
    public EditorResponse replay(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID receiptId, @RequestBody Replay request) {
        return EditorResponse.from(edits.replay(principal.userId(), projectId, receiptId, request));
    }
    public record EditorResponse(List<CanvasController.CanvasItemResponse> items, List<Group> groups, Receipt receipt) {
        static EditorResponse from(Editor editor) {
            return new EditorResponse(editor.items().stream().map(CanvasController.CanvasItemResponse::from).toList(), editor.groups(), editor.receipt());
        }
    }
}
