package dev.agenvas.task.api;

import dev.agenvas.artifact.domain.MediaDraft.VideoInputMode;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.VideoStudioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Owner-only workbench API. No operation accepts a project, node, or remote media URL. */
@RestController
@RequestMapping("/api/v1/video-studio")
public class VideoStudioController {
    private final VideoStudioService studio;

    public VideoStudioController(VideoStudioService studio) {
        this.studio = studio;
    }

    @GetMapping("/context")
    public ResponseEntity<VideoStudioService.Context> context(@AuthenticationPrincipal AdminPrincipal principal) {
        return privateResponse(studio.context(principal.userId()));
    }

    @PostMapping(path = "/frames", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VideoStudioService.Frame> upload(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestPart(name = "file", required = false) MultipartFile file, MultipartHttpServletRequest request)
            throws IOException {
        try (var input = singleFile(file, request)) {
            return created(studio.upload(principal.userId(), input));
        }
    }

    @PostMapping(path = "/references/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VideoStudioService.ReferenceAsset> uploadReference(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable Asset.MediaKind kind, @RequestPart(name = "file", required = false) MultipartFile file,
            MultipartHttpServletRequest request) throws IOException {
        try (var input = singleFile(file, request)) {
            return created(studio.uploadReference(principal.userId(), kind, input));
        }
    }

    private static InputStream singleFile(MultipartFile file, MultipartHttpServletRequest request) throws IOException {
        if (file == null || request.getMultiFileMap().size() != 1 || request.getFiles("file").size() != 1
                || request.getParameterMap().keySet().stream().anyMatch(name -> !"_csrf".equals(name)))
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.video-studio.title"), ApiMessage.of("api.video-studio.invalid-request"), false);
        return file.getInputStream();
    }

    @PostMapping(path = "/generations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VideoStudioService.GenerationView> accept(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateGeneration request) {
        return created(studio.accept(principal.userId(), request.command(), key));
    }

    @GetMapping("/generations")
    public ResponseEntity<VideoStudioService.GenerationPage> list(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam(required = false) String before, @RequestParam(required = false) Integer limit) {
        return privateResponse(studio.list(principal.userId(), before, limit));
    }

    @GetMapping("/generations/{generationId}")
    public ResponseEntity<VideoStudioService.GenerationView> get(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID generationId) {
        return privateResponse(studio.get(principal.userId(), generationId));
    }

    public record CreateGeneration(@NotNull UUID capabilityId, @NotNull JsonNode capabilityVersion,
            @NotNull @Size(max = 8000) String prompt, @NotNull VideoInputMode mode, JsonNode durationSeconds,
            String aspectRatio, UUID startFrameVersionId, UUID endFrameVersionId,
            @Size(max = 14) List<@NotNull @Valid ReferenceInput> references,
            @Size(max = 12) String videoResolution, JsonNode dynamicValues) {
        // Binding a floating point wire value straight to Integer would silently truncate the billed duration.
        @AssertTrue
        public boolean isIntegerInputs() {
            return (durationSeconds == null || durationSeconds.isNull()
                    || durationSeconds.isIntegralNumber() && durationSeconds.canConvertToInt())
                    && capabilityVersion != null && capabilityVersion.isIntegralNumber() && capabilityVersion.canConvertToInt();
        }

        VideoStudioService.Command command() {
            return new VideoStudioService.Command(capabilityId, capabilityVersion.intValue(), prompt, mode,
                    durationSeconds == null || durationSeconds.isNull() ? null : durationSeconds.intValue(),
                    aspectRatio, startFrameVersionId, endFrameVersionId,
                    references == null ? List.of() : references.stream().map(ReferenceInput::command).toList(),
                    videoResolution, dynamicValues == null ? JsonNodeFactory.instance.objectNode() : dynamicValues);
        }

        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown video Studio request field");
        }
    }

    public record ReferenceInput(@NotNull Asset.MediaKind kind, @NotNull UUID versionId,
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,63}$") String fieldKey) {
        VideoStudioService.ReferenceInput command() {
            return new VideoStudioService.ReferenceInput(kind, versionId, fieldKey);
        }

        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown video Studio reference field");
        }
    }

    private static <T> ResponseEntity<T> privateResponse(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private static <T> ResponseEntity<T> created(T value) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(value);
    }
}
