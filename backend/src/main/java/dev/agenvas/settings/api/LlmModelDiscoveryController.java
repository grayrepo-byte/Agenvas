package dev.agenvas.settings.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.settings.application.LlmModelDiscoveryService;
import dev.agenvas.settings.infrastructure.ModelMetadataClient;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Outbound metadata is an explicit administrator command; responses contain no credential material. */
@RestController
@RequestMapping("/api/v1/settings/llm/models")
public class LlmModelDiscoveryController {
    private final LlmModelDiscoveryService discovery;
    public LlmModelDiscoveryController(LlmModelDiscoveryService discovery) {
        this.discovery = discovery;
    }
    @PostMapping
    public ResponseEntity<LlmModelDiscoveryService.Preview> preview(@AuthenticationPrincipal AdminPrincipal admin,
            @RequestBody LlmModelDiscoveryService.PreviewRequest request) {
        Objects.requireNonNull(admin, "Authenticated administrator required");
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(discovery.preview(request));
        } catch (ModelMetadataClient.MetadataFailure failure) {
            var status = switch (failure.getMessage()) {
                case "MODEL_METADATA_BUSY" -> HttpStatus.TOO_MANY_REQUESTS;
                case "MODEL_METADATA_INVALID" -> HttpStatus.BAD_REQUEST;
                case "MODEL_METADATA_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
                default -> HttpStatus.BAD_GATEWAY;
            };
            throw problem(status, failure.getMessage());
        } catch (IllegalArgumentException invalid) {
            throw problem(HttpStatus.BAD_REQUEST, "MODEL_PREVIEW_INVALID");
        }
    }
    private static ApiProblemException problem(HttpStatus status, String code) {
        return new ApiProblemException(status, code, ApiMessage.of("api.model-metadata.title"),
                ApiMessage.of("api.model-metadata.detail"), false);
    }
}
