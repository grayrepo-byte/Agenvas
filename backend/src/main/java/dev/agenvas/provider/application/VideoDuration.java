package dev.agenvas.provider.application;

import java.time.Duration;
import tools.jackson.databind.JsonNode;

/** Reads the duration frozen at approval without changing units of historical tasks. */
final class VideoDuration {

    private VideoDuration() {}

    static Duration fromFrozenTask(JsonNode input) {
        int schemaVersion = input.path("schemaVersion").asInt(1);
        if (schemaVersion == 1) {
            JsonNode milliseconds = input.path("durationMs");
            if (!milliseconds.isIntegralNumber() || milliseconds.longValue() < 100
                    || milliseconds.longValue() > 30_000) {
                throw new IllegalArgumentException("Legacy video Task lacks pinned milliseconds");
            }
            return Duration.ofMillis(milliseconds.longValue());
        }
        if (schemaVersion == 2) {
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
