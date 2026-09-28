package dev.agenvas.provider.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Capability;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Connection;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.ConnectionVersion;
import dev.agenvas.task.domain.Task;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Administrator catalog API. All views are explicit allowlists without encrypted columns. */
@RestController
@RequestMapping("/api/v1/settings")
public class MediaCapabilityController {

    private final MediaCapabilityService catalog;
    private final MediaAdapterRegistry adapters;
    private final ObjectMapper mapper;

    public MediaCapabilityController(MediaCapabilityService catalog,
            MediaAdapterRegistry adapters, ObjectMapper mapper) {
        this.catalog = catalog;
        this.adapters = adapters;
        this.mapper = mapper;
    }

    @GetMapping("/media-connections")
    public ResponseEntity<MediaSettingsResponse> list(
            @AuthenticationPrincipal AdminPrincipal administrator) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        return response();
    }

    @PostMapping("/media-connections")
    public ResponseEntity<MediaSettingsResponse> create(
            @AuthenticationPrincipal AdminPrincipal administrator,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateConnectionRequest request) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        catalog.createConnection(idempotencyKey, request.name(), request.platform(),
                request.origin(), request.apiKey());
        return response();
    }

    @PutMapping("/media-connections/{connectionId}")
    public ResponseEntity<MediaSettingsResponse> update(
            @AuthenticationPrincipal AdminPrincipal administrator,
            @PathVariable UUID connectionId,
            @Valid @RequestBody UpdateConnectionRequest request) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        catalog.updateConnection(connectionId, request.expectedVersion(), request.name(),
                request.enabled(), request.origin(), request.apiKey());
        return response();
    }

    @PostMapping("/media-connections/{connectionId}/capabilities")
    public ResponseEntity<MediaSettingsResponse> createCapability(
            @AuthenticationPrincipal AdminPrincipal administrator,
            @PathVariable UUID connectionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateCapabilityRequest request) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        catalog.publishCapability(idempotencyKey, connectionId, request.name(),
                request.adapterId(), request.settings());
        return response();
    }

    @PutMapping("/media-connections/{connectionId}/capabilities/{capabilityId}")
    public ResponseEntity<MediaSettingsResponse> updateCapability(
            @AuthenticationPrincipal AdminPrincipal administrator,
            @PathVariable UUID connectionId, @PathVariable UUID capabilityId,
            @Valid @RequestBody UpdateCapabilityRequest request) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        catalog.updateCapability(connectionId, capabilityId, request.expectedVersion(),
                request.name(), request.enabled(), request.adapterId(), request.settings());
        return response();
    }

    @PutMapping("/media-defaults/{kind}")
    public ResponseEntity<MediaSettingsResponse> setDefault(
            @AuthenticationPrincipal AdminPrincipal administrator,
            @PathVariable Task.Kind kind, @Valid @RequestBody SetDefaultRequest request) {
        Objects.requireNonNull(administrator, "Authenticated administrator required");
        catalog.setDefault(kind, request.expectedVersion(), request.capabilityId());
        return response();
    }

    private ResponseEntity<MediaSettingsResponse> response() {
        List<ConnectionView> connections = catalog.connections().stream().map(this::view).toList();
        List<DefaultView> defaults = Arrays.stream(new Task.Kind[] {
                Task.Kind.IMAGE_GENERATION, Task.Kind.VIDEO_GENERATION})
                .map(kind -> new DefaultView(kind, catalog.defaultCapabilityId(kind),
                        catalog.defaultVersion(kind))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new MediaSettingsResponse(connections, defaults));
    }

    private ConnectionView view(Connection connection) {
        ConnectionVersion version = catalog.getConnectionVersion(connection.id(),
                connection.currentVersion()).orElseThrow();
        List<CapabilityView> capabilities = catalog.capabilities(connection.id()).stream()
                .map(this::view).toList();
        return new ConnectionView(connection.id(), connection.name(), connection.platform().name(),
                connection.enabled(), connection.version(), connection.currentVersion(),
                version.origin(), version.keyMask(), "NOT_CHECKED", false, capabilities);
    }

    private CapabilityView view(Capability capability) {
        var snapshot = catalog.capabilitySnapshot(capability.id());
        var declaration = adapters.declaration(snapshot.adapterId());
        JsonNode settings = mapper.readTree(snapshot.specJson()).path("settings");
        return new CapabilityView(capability.id(), capability.name(), capability.enabled(),
                capability.version(), capability.currentVersion(), snapshot.adapterId(),
                declaration.kind(), declaration.minimumSeconds(), declaration.maximumSeconds(),
                declaration.maxReferenceImages(), declaration.supportedVideoInputModes().stream()
                        .sorted().toList(), declaration.defaultVideoInputMode(),
                declaration.supportsEndFrame(), snapshot.mappingSha256(), settings.isMissingNode()
                        ? mapper.createObjectNode() : settings);
    }

    public record CreateConnectionRequest(@NotBlank @Size(max = 160) String name,
            @NotBlank String platform, String origin, String apiKey) {}
    public record UpdateConnectionRequest(@Min(0) long expectedVersion,
            @NotBlank @Size(max = 160) String name, boolean enabled,
            String origin, String apiKey) {}
    public record CreateCapabilityRequest(@NotBlank @Size(max = 160) String name,
            @NotBlank String adapterId, JsonNode settings) {}
    public record UpdateCapabilityRequest(@Min(0) long expectedVersion,
            @NotBlank @Size(max = 160) String name, boolean enabled,
            @NotBlank String adapterId, JsonNode settings) {}
    public record SetDefaultRequest(@Min(0) long expectedVersion, UUID capabilityId) {}
    public record MediaSettingsResponse(List<ConnectionView> connections,
            List<DefaultView> defaults) {}
    public record ConnectionView(UUID id, String name, String platform, boolean enabled,
            long version, int connectionVersion, String origin, String keyMask,
            String connectivityStatus, boolean realGenerationTested,
            List<CapabilityView> capabilities) {}
    public record CapabilityView(UUID id, String name, boolean enabled, long version,
            int capabilityVersion, String adapterId, Task.Kind kind,
            int minimumSeconds, int maximumSeconds, int maxReferenceImages,
            List<String> supportedVideoInputModes, String defaultVideoInputMode,
            boolean supportsEndFrame, String mappingSha256,
            JsonNode settings) {}
    public record DefaultView(Task.Kind kind, UUID capabilityId, long version) {}
}
