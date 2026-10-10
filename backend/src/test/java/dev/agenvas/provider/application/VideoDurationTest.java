package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class VideoDurationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requiresWholeSecondsInNewFrozenTasks() {
        assertThat(VideoDuration.fromFrozenTask(mapper.readTree("""
                {"schemaVersion":2,"durationSeconds":5}
                """))).isEqualTo(Duration.ofSeconds(5));
        assertThat(VideoDuration.fromFrozenTask(mapper.readTree("""
                {"schemaVersion":3,"durationSeconds":5}
                """))).isEqualTo(Duration.ofSeconds(5));
        for (String invalid : new String[] {
                "{\"schemaVersion\":1,\"durationMs\":1250}",
                "{\"durationMs\":5000}",
                "{\"schemaVersion\":2,\"durationSeconds\":1.25}",
                "{\"schemaVersion\":2,\"durationSeconds\":0}",
                "{\"schemaVersion\":2,\"durationSeconds\":31}",
                "{\"schemaVersion\":2,\"durationMs\":5000}"}) {
            assertThatThrownBy(() -> VideoDuration.fromFrozenTask(mapper.readTree(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsWholeSecondsFromStyledAndMixedReferenceSchemasWithoutUsingLegacyMilliseconds() {
        assertThat(VideoDuration.fromFrozenTask(mapper.readTree("""
                {"schemaVersion":4,"durationSeconds":5,
                 "mediaInput":{"style":{"id":"00000000-0000-0000-0000-000000000308"}}}
                """))).isEqualTo(Duration.ofSeconds(5));
        assertThat(VideoDuration.fromFrozenTask(mapper.readTree("""
                {"schemaVersion":5,"durationSeconds":5,"mediaInput":{"images":[]}}
                """))).isEqualTo(Duration.ofSeconds(5));
        for (String invalid : new String[] {
                "{\"schemaVersion\":4,\"durationSeconds\":1.25}",
                "{\"schemaVersion\":4,\"durationSeconds\":0}",
                "{\"schemaVersion\":4,\"durationSeconds\":31}",
                "{\"schemaVersion\":4,\"durationMs\":5000}"}) {
            assertThatThrownBy(() -> VideoDuration.fromFrozenTask(mapper.readTree(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 30})
    void readsPinnedWholeSecondsFromImageRelaySchema(int seconds) {
        var input = mapper.createObjectNode().put("schemaVersion", 6)
                .put("durationSeconds", seconds).put("durationMs", 999);
        input.putObject("mediaInput").putArray("images");
        assertThat(VideoDuration.fromFrozenTask(input)).isEqualTo(Duration.ofSeconds(seconds));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemaVersion\":6,\"durationSeconds\":1.25}",
            "{\"schemaVersion\":6,\"durationSeconds\":0}",
            "{\"schemaVersion\":6,\"durationSeconds\":31}",
            "{\"schemaVersion\":6,\"durationMs\":5000}",
            "{\"schemaVersion\":7,\"durationSeconds\":5}"})
    void rejectsInvalidRelayDurationsAndUnsupportedSchemas(String invalid) {
        assertThatThrownBy(() -> VideoDuration.fromFrozenTask(mapper.readTree(invalid)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
