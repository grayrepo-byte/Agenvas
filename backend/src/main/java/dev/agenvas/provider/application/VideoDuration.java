package dev.agenvas.provider.application;

import java.time.Duration;
import tools.jackson.databind.JsonNode;

/** Reads the approved whole-second duration from the immutable task input. */
final class VideoDuration {

    private static final int FIRST_WHOLE_SECONDS_SCHEMA_VERSION = 2;
    private static final int STYLED_INPUT_SCHEMA_VERSION = 4;

    private VideoDuration() {}

    static Duration fromFrozenTask(JsonNode input) {
        int schemaVersion = input.path("schemaVersion")
                .asInt(-1);
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
