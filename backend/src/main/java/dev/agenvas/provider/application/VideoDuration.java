package dev.agenvas.provider.application;

import java.time.Duration;
import tools.jackson.databind.JsonNode;

/** Reads the duration frozen at approval without changing units of historical tasks. */
final class VideoDuration {

    private static final int LEGACY_MILLISECONDS_SCHEMA_VERSION = 1;
    private static final int FIRST_WHOLE_SECONDS_SCHEMA_VERSION = 2;
    private static final int STYLED_INPUT_SCHEMA_VERSION = 4;

    private VideoDuration() {}

    static Duration fromFrozenTask(JsonNode input) {
        int schemaVersion = input.path("schemaVersion")
                .asInt(LEGACY_MILLISECONDS_SCHEMA_VERSION);
        if (schemaVersion == LEGACY_MILLISECONDS_SCHEMA_VERSION) {
            JsonNode milliseconds = input.path("durationMs");
            if (!milliseconds.isIntegralNumber() || milliseconds.longValue() < 100
                    || milliseconds.longValue() > 30_000) {
                throw new IllegalArgumentException("Legacy video Task lacks pinned milliseconds");
            }
            return Duration.ofMillis(milliseconds.longValue());
        }
        // Schema 4 adds the style snapshot; the frozen video duration remains whole seconds.
        if (schemaVersion >= FIRST_WHOLE_SECONDS_SCHEMA_VERSION
                && schemaVersion <= STYLED_INPUT_SCHEMA_VERSION) {
            JsonNode seconds = input.path("durationSeconds");
            if (!seconds.isIntegralNumber() || seconds.longValue() < 1
                    || seconds.longValue() > 30) {
                throw new IllegalArgumentException("Video Task lacks pinned whole seconds");
            }
            return Duration.ofSeconds(seconds.longValue());
        }
        throw new IllegalArgumentException("Unsupported frozen video Task schema version");
    }
}
