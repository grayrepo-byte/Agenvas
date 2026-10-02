package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VideoDurationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void interpretsFrozenLegacyMillisecondsWithoutRounding() {
        assertThat(VideoDuration.fromFrozenTask(mapper.readTree("""
                {"schemaVersion":1,"durationMs":1250}
                """))).isEqualTo(Duration.ofMillis(1250));
        assertThat(VideoDuration.fromFrozenTask(mapper.readTree("""
                {"durationMs":5000}
                """))).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void requiresWholeSecondsInNewFrozenTasks() {
        assertThat(VideoDuration.fromFrozenTask(mapper.readTree("""
                {"schemaVersion":2,"durationSeconds":5}
                """))).isEqualTo(Duration.ofSeconds(5));
        assertThat(VideoDuration.fromFrozenTask(mapper.readTree("""
                {"schemaVersion":3,"durationSeconds":5}
                """))).isEqualTo(Duration.ofSeconds(5));
        for (String invalid : new String[] {
                "{\"schemaVersion\":2,\"durationSeconds\":1.25}",
                "{\"schemaVersion\":2,\"durationSeconds\":0}",
                "{\"schemaVersion\":2,\"durationSeconds\":31}",
                "{\"schemaVersion\":2,\"durationMs\":5000}"}) {
            assertThatThrownBy(() -> VideoDuration.fromFrozenTask(mapper.readTree(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsWholeSecondsFromStyledSchemaFourWithoutUsingLegacyMilliseconds() {
        assertThat(VideoDuration.fromFrozenTask(mapper.readTree("""
                {"schemaVersion":4,"durationSeconds":5,
                 "mediaInput":{"style":{"id":"00000000-0000-0000-0000-000000000308"}}}
                """))).isEqualTo(Duration.ofSeconds(5));
        for (String invalid : new String[] {
                "{\"schemaVersion\":4,\"durationSeconds\":1.25}",
                "{\"schemaVersion\":4,\"durationSeconds\":0}",
                "{\"schemaVersion\":4,\"durationSeconds\":31}",
                "{\"schemaVersion\":4,\"durationMs\":5000}",
                "{\"schemaVersion\":5,\"durationSeconds\":5}"}) {
            assertThatThrownBy(() -> VideoDuration.fromFrozenTask(mapper.readTree(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
