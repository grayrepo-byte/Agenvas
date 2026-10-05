package dev.agenvas.provider.application;

import java.time.Duration;
import tools.jackson.databind.JsonNode;

/** Reads the approved whole-second duration from the immutable task input. */
final class VideoDuration {

    private static final int FIRST_WHOLE_SECONDS_SCHEMA_VERSION = 2;
    private static final int MEDIA_INPUT_SCHEMA_VERSION = 5;
    private static final int MINIMUM_SECONDS = 1;
    private static final int MAXIMUM_SECONDS = 30;

    private VideoDuration() {}

    static Duration fromFrozenTask(JsonNode input) {
        int schemaVersion = input.path("schemaVersion")
                .asInt(-1);
        // Schema 4 adds styles and schema 5 mixed references; both keep whole-second duration.
        if (schemaVersion >= FIRST_WHOLE_SECONDS_SCHEMA_VERSION
                && schemaVersion <= MEDIA_INPUT_SCHEMA_VERSION) {
            JsonNode seconds = input.path("durationSeconds");
            if (!seconds.isIntegralNumber() || seconds.longValue() < MINIMUM_SECONDS
                    || seconds.longValue() > MAXIMUM_SECONDS) {
                throw new IllegalArgumentException("Video Task lacks pinned whole seconds");
            }
            return Duration.ofSeconds(seconds.longValue());
        }
        throw new IllegalArgumentException("Unsupported frozen video Task schema version");
    }
}
