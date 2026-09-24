package dev.agenvas.artifact.api;

import dev.agenvas.artifact.application.ShotRedoService;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated manual handoff boundary for one shot's local content revisions. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/shots")
public class ShotRedoController {

    private final ShotRedoService redo;

    public ShotRedoController(ShotRedoService redo) {
        this.redo = redo;
    }

    /** Saves a scoped new content version; it does not bypass later media-plan approval. */
    @PostMapping("/{shotId}/revisions")
    public ResponseEntity<Response> revise(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID shotId,
            @Valid @RequestBody Request request) {
        ShotRedoService.Result result = redo.revise(principal.userId(), projectId, shotId,
                new ShotRedoService.Request(request.expectedShotVersionId(),
                        request.expectedShotArtifactVersion(), request.description(),
                        request.camera(), request.action(), request.durationMs(),
                        request.scene() == null ? null : new ShotRedoService.SceneEdit(
                                request.scene().name(), request.scene().location(),
                                request.scene().timeOfDay(), request.scene().lighting(),
                                request.scene().style())));
        return ResponseEntity.status(HttpStatus.CREATED).body(new Response(
                ArtifactController.ArtifactResponse.from(result.shot()),
                result.scene() == null ? null
                        : ArtifactController.ArtifactResponse.from(result.scene())));
    }

    /** Optimistic target plus complete shot fields, with optional bounded scene changes. */
    public record Request(@NotNull UUID expectedShotVersionId,
            @PositiveOrZero long expectedShotArtifactVersion,
            @NotBlank @Size(max = 4000) String description,
            @NotBlank @Size(max = 1000) String camera,
            @NotBlank @Size(max = 2000) String action,
            Integer durationMs,
            @Valid SceneEdit scene) {}

    /** Fields left null inherit the exact currently selected scene version. */
    public record SceneEdit(@Size(min = 1, max = 120) String name,
            @Size(min = 1, max = 500) String location,
            @Size(min = 1, max = 80) String timeOfDay,
            @Size(min = 1, max = 1000) String lighting,
            @Size(min = 1, max = 1000) String style) {}

    /** Result contains only the target shot and the optional new shared-scene revision. */
    public record Response(ArtifactController.ArtifactResponse shot,
            ArtifactController.ArtifactResponse scene) {}
}
