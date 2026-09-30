package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class SystemLogBufferTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC);

    @Test void retainsNewestMatchesInOrderAndReportsEvictions() {
        var logs = new SystemLogBuffer(CLOCK, 3);
        logs.append(SystemLogBuffer.Stream.STDOUT, "old", false);
        logs.append(SystemLogBuffer.Stream.STDERR, "Error one", false);
        logs.append(SystemLogBuffer.Stream.STDOUT, "info", false);
        logs.append(SystemLogBuffer.Stream.STDERR, "Error two", true);
        var result = logs.snapshot(SystemLogBuffer.Stream.STDERR, " ERROR ", 1);
        assertThat(result.capacity()).isEqualTo(3);
        assertThat(result.retainedCount()).isEqualTo(3);
        assertThat(result.droppedCount()).isEqualTo(1);
        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.sequence()).isEqualTo(4);
            assertThat(entry.message()).isEqualTo("Error two");
            assertThat(entry.truncated()).isTrue();
            assertThat(entry.recordedAt()).isEqualTo(CLOCK.instant());
        });
        assertThat(logs.snapshot(null, null, 3).entries()).extracting(SystemLogBuffer.Entry::sequence)
                .containsExactly(2L, 3L, 4L);
        assertThat(result.processId()).isNotEqualTo(new SystemLogBuffer(CLOCK).snapshot(null, null, 1).processId());
    }

    @Test void masksCredentialsBeforeStorageAndSearch() {
        var logs = new SystemLogBuffer(CLOCK);
        for (String line : new String[]{
                "Authorization: Bearer private-value",
                "Cookie: session=private-value; other=hidden",
                "apiKey=private-value password='private-value'",
                "{\"apiKey\":\"private-value\",\"message\":\"ok\"}",
                "{\"message\":\"apiKey=\\\"private-value\\\"\"}",
                "AGENVAS_CREDENTIAL_MASTER_KEY=private-value",
                "AGENVAS_CREDENTIAL_PREVIOUS_KEYS=1=private-value",
                "clientSecret=part one private-value",
                "accessToken=private-value",
                "masterKeyBase64=private-value",
                "Bearer private-value sk-private-value AIzaprivate-value",
                "https://user:private-value@example.com/output?signature=private-value",
                "\u001B[31mapiKey=private-value\u001B[0m"}) {
            logs.append(SystemLogBuffer.Stream.STDOUT, line, false);
        }
        assertThat(logs.snapshot(null, "private-value", 100).entries()).isEmpty();
        assertThat(logs.snapshot(null, null, 100).entries()).allSatisfy(entry -> {
            assertThat(entry.message()).contains("[REDACTED]").doesNotContain("private-value", "\u001B");
        });
    }

    @Test void validatesBoundsAndUsesLiteralSearch() {
        var logs = new SystemLogBuffer(CLOCK);
        assertThatThrownBy(() -> logs.snapshot(null, null, 0)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> logs.snapshot(null, null, SystemLogBuffer.MAX_LIMIT + 1)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> logs.snapshot(null, "x".repeat(SystemLogBuffer.MAX_SEARCH_LENGTH + 1), 1)).isInstanceOf(ApiProblemException.class);
        logs.append(SystemLogBuffer.Stream.STDOUT, "[literal]", false);
        assertThat(logs.snapshot(null, "[literal]", 1).entries()).hasSize(1);
        assertThat(logs.snapshot(null, ".*", 1).entries()).isEmpty();
    }

    @Test void concurrentWritersAndSnapshotsKeepConsistentWatermarks() throws Exception {
        var logs = new SystemLogBuffer(CLOCK, 10);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { for (int i = 0; i < 100; i++) logs.append(SystemLogBuffer.Stream.STDOUT, "out", false); });
            var second = executor.submit(() -> { for (int i = 0; i < 100; i++) {
                logs.append(SystemLogBuffer.Stream.STDERR, "err", false);
                var view = logs.snapshot(null, null, 10);
                assertThat(view.retainedCount()).isEqualTo(view.entries().size());
            } });
            first.get(); second.get();
        }
        var result = logs.snapshot(null, null, 10);
        assertThat(result.droppedCount()).isEqualTo(190);
        assertThat(result.entries()).extracting(SystemLogBuffer.Entry::sequence)
                .containsExactly(191L, 192L, 193L, 194L, 195L, 196L, 197L, 198L, 199L, 200L);
    }
}
