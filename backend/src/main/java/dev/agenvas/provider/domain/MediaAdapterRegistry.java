package dev.agenvas.provider.domain;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Only compiled adapter IDs can be published; runtime implementations register as Spring beans. */
@Component
public final class MediaAdapterRegistry {

    public static final String SEED_AUDIO_1 = "VOLC_SEED_AUDIO_1";
    public static final String SEEDANCE_2 = "ARK_SEEDANCE_2_I2V";
    public static final int SEEDANCE_MAX_AUDIO_BYTES = 15 * 1024 * 1024;
    public static final int SEEDANCE_MIN_AUDIO_DURATION_MS = 2_000;
    public static final int SEEDANCE_MAX_AUDIO_DURATION_MS = 15_000;
    public static final int SEEDANCE_MAX_REFERENCE_VIDEOS = 3;
    public static final long SEEDANCE_MAX_VIDEO_BYTES = 200L * 1024 * 1024;
    public static final int SEEDANCE_MIN_VIDEO_DURATION_MS = 2_000;
    public static final int SEEDANCE_MAX_VIDEO_DURATION_MS = 15_000;
    public static final int SEEDANCE_MIN_VIDEO_SIDE = 300;
    public static final int SEEDANCE_MAX_VIDEO_SIDE = 6_000;
    public static final int SEEDANCE_MIN_VIDEO_PIXELS = 407_696;
    public static final int SEEDANCE_MAX_VIDEO_PIXELS = 8_295_044;
    public static final int SEEDANCE_MIN_VIDEO_FPS = 24;
    public static final int SEEDANCE_MAX_VIDEO_FPS = 60;
    public static final double SEEDANCE_MIN_VIDEO_RATIO = 0.4;
    public static final double SEEDANCE_MAX_VIDEO_RATIO = 2.5;
    public static final String LOCAL_IMAGE_PROCESSOR = "LOCAL_IMAGE_PROCESSOR";
    public static final String LOCAL_VIDEO_PROCESSOR = "LOCAL_VIDEO_PROCESSOR";
    public static final String LOCAL_VIDEO_AUDIO_EXTRACTOR = "LOCAL_VIDEO_AUDIO_EXTRACTOR";
    public static final String LOCAL_VIDEO_COMPOSITOR = "LOCAL_VIDEO_COMPOSITOR";
    public static final String LOCAL_AUDIO_TRIMMER = "LOCAL_AUDIO_TRIMMER";

    public static final String OPENAI_GPT_IMAGE_2 = "OPENAI_GPT_IMAGE_2";
    public static final String GOOGLE_NANO_BANANA_2 = "GOOGLE_NANO_BANANA_2";
    public static final String COMFY_IMAGE_V1 = "COMFY_IMAGE_V1";
    public static final String RUNNINGHUB_IMAGE = "RUNNINGHUB_IMAGE";
    public static final String RUNNINGHUB_VIDEO = "RUNNINGHUB_VIDEO";
    public static final String RUNNINGHUB_AUDIO = "RUNNINGHUB_AUDIO";
    public static final Set<String> RUNNINGHUB_ADAPTERS = Set.of(RUNNINGHUB_IMAGE, RUNNINGHUB_VIDEO, RUNNINGHUB_AUDIO);
    public static final int RUNNINGHUB_MAX_INPUTS = 14;
    public static final int RUNNINGHUB_MAX_VIDEO_SECONDS = 60;

    /** Product bounds verified against each fixed third-party request protocol. */
    public static final int OPENAI_MAX_REFERENCE_IMAGES = 4;
    public static final int GOOGLE_MAX_REFERENCE_IMAGES = 14;
    public static final int COMFY_MAX_PROMPT_LENGTH = 8_000;

    public record Declaration(MediaPlatform platform, Task.Kind kind, int minimumSeconds,
            int maximumSeconds, boolean originRequired, int maxReferenceImages,
            Set<String> supportedVideoInputModes, String defaultVideoInputMode,
            boolean supportsEndFrame, Set<String> supportedImageAspectRatios,
            Set<String> supportedImageResolutions, Set<String> supportedImageQualities,
            boolean supportsTransparentBackground, boolean supportsImageMask, int maxReferenceAudios,
            int maxReferenceVideos) {
        public Declaration {
            java.util.Objects.requireNonNull(platform, "Media platform");
            if (!Set.of(Task.Kind.IMAGE_GENERATION, Task.Kind.VIDEO_GENERATION, Task.Kind.AUDIO_GENERATION)
                    .contains(kind))
                throw new IllegalArgumentException("Declaration must describe a media task");
            supportedVideoInputModes = Set.copyOf(supportedVideoInputModes);
            supportedImageAspectRatios = Set.copyOf(supportedImageAspectRatios);
            supportedImageResolutions = Set.copyOf(supportedImageResolutions);
            supportedImageQualities = Set.copyOf(supportedImageQualities);
            if (minimumSeconds < 0 || maximumSeconds < minimumSeconds
                    || maxReferenceImages < 0 || maxReferenceAudios < 0 || maxReferenceVideos < 0)
                throw new IllegalArgumentException("Invalid media declaration bounds");
            if (defaultVideoInputMode != null && !supportedVideoInputModes.contains(defaultVideoInputMode))
                throw new IllegalArgumentException("Default video mode is not supported");
        }
    }

    private final Map<String, MediaAdapter> implementations;
    private final Map<String, Declaration> declarations;
    private final Map<String, dev.agenvas.shared.http.HttpTimeouts> httpTimeoutDefaults;

    public MediaAdapterRegistry(List<MediaAdapter> adapters) {
        var installed = new java.util.LinkedHashMap<String, MediaAdapter>();
        var declared = new java.util.LinkedHashMap<>(BuiltinMediaAdapters.declarations());
        var timeouts = new java.util.LinkedHashMap<>(MediaHttpTimeouts.defaults());
        for (MediaAdapter adapter : adapters) {
            java.util.Objects.requireNonNull(adapter, "Media adapter");
            String id = adapter.adapterId();
            if (id == null || !id.matches("[A-Z][A-Z0-9_]{0,119}"))
                throw new IllegalStateException("Invalid media adapter ID");
            if (installed.putIfAbsent(id, adapter) != null)
                throw new IllegalStateException("Duplicate media adapter: " + id);
            Declaration value = java.util.Objects.requireNonNull(adapter.declaration(), "Media declaration: " + id);
            var defaults = adapter.publicationHttpTimeouts();
            if (defaults != null) {
                if (value.platform() == MediaPlatform.LOCAL || value.platform() == MediaPlatform.MOCK)
                    throw new IllegalStateException("Local media adapter cannot declare HTTP timeouts: " + id);
                var previous = timeouts.putIfAbsent(id, defaults);
                if (previous != null && !previous.equals(defaults))
                    throw new IllegalStateException("Conflicting media HTTP defaults: " + id);
            }
            Declaration builtin = declared.putIfAbsent(id, value);
            if (builtin != null && !builtin.equals(value))
                throw new IllegalStateException("Conflicting media declaration: " + id);
        }
        this.implementations = Map.copyOf(installed);
        this.declarations = Map.copyOf(declared);
        this.httpTimeoutDefaults = Map.copyOf(timeouts);
    }

    /** Publication defaults never mutate pinned settings or historical adapter fallbacks. */
    public Map<String, dev.agenvas.shared.http.HttpTimeouts> httpTimeoutDefaults() {
        return httpTimeoutDefaults;
    }

    public Declaration declaration(String adapterId) {
        Declaration result = adapterId == null ? null : declarations.get(adapterId);
        if (result == null) {
            throw unsupported();
        }
        return result;
    }

    public MediaAdapter require(String adapterId) {
        declaration(adapterId);
        MediaAdapter adapter = implementations.get(adapterId);
        if (adapter == null) {
            throw unsupported();
        }
        return adapter;
    }

    /** Only an installed adapter can authorize publication for its own persisted binding. */
    public java.util.Optional<PublicDiagnostic> publicDiagnostic(String adapterId, ProviderFailure failure) {
        MediaAdapter adapter = adapterId == null ? null : implementations.get(adapterId);
        return adapter != null && failure != null && adapter.permitsPublicDiagnostic(failure)
                ? java.util.Optional.of(new PublicDiagnostic(adapterId, failure))
                : java.util.Optional.empty();
    }

    public static final class PublicDiagnostic {
        private final String adapterId;
        private final ProviderFailure failure;

        private PublicDiagnostic(String adapterId, ProviderFailure failure) {
            this.adapterId = adapterId;
            this.failure = failure;
        }
        public String adapterId() {
            return adapterId;
        }
        public ProviderFailure failure() {
            return failure;
        }
    }

    private static ApiProblemException unsupported() {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PROVIDER_UNSUPPORTED_CAPABILITY",
                ApiMessage.of("api.media-adapter-registry.media-capabilities-are-not-available"),
                ApiMessage.of(
                        "api.media-adapter-registry.this-media-adapter-is-not-installed-by-the-current-application"),
                false);
    }
}
