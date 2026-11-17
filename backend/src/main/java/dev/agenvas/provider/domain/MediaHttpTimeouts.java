package dev.agenvas.provider.domain;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.http.HttpTimeouts;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** Versioned media transport policy; absent historical settings never acquire new defaults. */
public final class MediaHttpTimeouts {
    public static final String SETTINGS_KEY = "httpTimeouts";
    private static final Set<String> FIELDS = Set.of("connectSeconds", "writeSeconds", "readSeconds", "callSeconds");

    private static final HttpTimeouts SYNCHRONOUS_IMAGE = new HttpTimeouts(10, 300, 300, 300);
    private static final HttpTimeouts SHORT_ASYNC = new HttpTimeouts(10, 120, 30, 120);
    private static final HttpTimeouts RUNNINGHUB = new HttpTimeouts(10, 120, 60, 120);
    private static final HttpTimeouts COMFYUI = new HttpTimeouts(5, 20, 20, 20);
    private static final HttpTimeouts WAN = new HttpTimeouts(10, 180, 45, 180);
    private static final HttpTimeouts GOOGLE_VIDEO = new HttpTimeouts(10, 600, 480, 600);
    // New publication defaults align writes with the whole-call budget. They are not legacy fallbacks.
    private static final Map<String, HttpTimeouts> DEFAULTS = Map.ofEntries(
            Map.entry(MediaAdapterRegistry.OPENAI_GPT_IMAGE_2, SYNCHRONOUS_IMAGE),
            Map.entry(MediaAdapterRegistry.GOOGLE_NANO_BANANA_2, SYNCHRONOUS_IMAGE),
            Map.entry(MediaAdapterRegistry.SEED_AUDIO_1, SYNCHRONOUS_IMAGE),
            Map.entry(MediaAdapterRegistry.SEEDANCE_2, new HttpTimeouts(10, 120, 10, 120)),
            Map.entry(SeedreamModels.ADAPTER_ID, new HttpTimeouts(10, 360, 300, 360)),
            Map.entry(GoogleVideoModels.VEO, GOOGLE_VIDEO), Map.entry(GoogleVideoModels.OMNI, GOOGLE_VIDEO),
            Map.entry(WanModels.IMAGE_ADAPTER_ID, WAN), Map.entry(WanModels.VIDEO_ADAPTER_ID, WAN),
            Map.entry(KlingModels.IMAGE_ADAPTER_ID, SHORT_ASYNC),
            Map.entry(KlingModels.VIDEO_ADAPTER_ID, SHORT_ASYNC),
            Map.entry(KlingProtocol.IMAGE_ADAPTER_ID, SHORT_ASYNC),
            Map.entry(KlingProtocol.VIDEO_ADAPTER_ID, SHORT_ASYNC),
            Map.entry(MiniMaxH3Protocol.ADAPTER_ID, SHORT_ASYNC),
            Map.entry(AutoDlWorkflows.ADAPTER_ID, SHORT_ASYNC),
            Map.entry(MediaAdapterRegistry.RUNNINGHUB_IMAGE, RUNNINGHUB),
            Map.entry(MediaAdapterRegistry.RUNNINGHUB_VIDEO, RUNNINGHUB),
            Map.entry(MediaAdapterRegistry.RUNNINGHUB_AUDIO, RUNNINGHUB),
            Map.entry(MediaAdapterRegistry.COMFY_IMAGE_V1, COMFYUI), Map.entry("COMFY_VIDEO_V1", COMFYUI));

    private MediaHttpTimeouts() {
    }

    /** Read only the pinned settings object. Null means preserve the adapter's API/download defaults. */
    public static HttpTimeouts configured(JsonNode settings) {
        JsonNode value = settings == null ? null : settings.get(SETTINGS_KEY);
        if (value == null)
            return null;
        if (!value.isObject() || !FIELDS.equals(value.propertyNames()))
            throw invalid();
        try {
            return new HttpTimeouts(integer(value, "connectSeconds"), integer(value, "writeSeconds"),
                    integer(value, "readSeconds"), integer(value, "callSeconds"));
        } catch (IllegalArgumentException invalidValue) {
            throw invalid();
        }
    }

    /** The settings API exposes this table for new/edited drafts; callers must not backfill old versions. */
    public static Map<String, HttpTimeouts> defaults() {
        return DEFAULTS;
    }

    private static int integer(JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
            throw invalid();
        return value.intValue();
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_UNSUPPORTED_INPUT",
                ApiMessage.of("api.media-capability-configuration.invalid-media-configuration"),
                ApiMessage.of("api.media-http-timeouts.invalid"), false);
    }
}
