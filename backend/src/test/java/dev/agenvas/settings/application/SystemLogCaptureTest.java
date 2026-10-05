package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class SystemLogCaptureTest {
    @Test void splitUtf8AndFlushPreserveConsoleAndCompleteLines() {
        var logs = new SystemLogBuffer(Clock.systemUTC());
        var original = new ByteArrayOutputStream();
        var output = new SystemLogCapture.LineOutput(new PrintStream(original), logs, SystemLogBuffer.Stream.STDERR);
        byte[] bytes = "调试中文 apiKey=private-value\r\nnext\n".getBytes(StandardCharsets.UTF_8);
        output.write(bytes, 0, 2);
        output.flush();
        assertThat(logs.snapshot(null, null, 10).entries()).isEmpty();
        for (int i = 2; i < bytes.length; i++) output.write(bytes[i]);
        assertThat(original.toByteArray()).isEqualTo(bytes);
        assertThat(logs.snapshot(null, null, 10).entries()).extracting(SystemLogBuffer.Entry::message)
                .containsExactly("调试中文 apiKey=[REDACTED]", "next");
        assertThat(logs.snapshot(null, null, 10).entries()).allSatisfy(entry ->
                assertThat(entry.stream()).isEqualTo(SystemLogBuffer.Stream.STDERR));
    }

    @Test void oversizeTailIsDiscardedUntilNewlineWithoutLosingFollowingLines() {
        var logs = new SystemLogBuffer(Clock.systemUTC());
        var original = new ByteArrayOutputStream();
        var output = new SystemLogCapture.LineOutput(new PrintStream(original), logs, SystemLogBuffer.Stream.STDOUT);
        byte[] bytes = ("x".repeat(SystemLogBuffer.MAX_LINE_BYTES + 100) + "\nhealthy\n").getBytes(StandardCharsets.UTF_8);
        output.write(bytes, 0, bytes.length);
        var entries = logs.snapshot(null, null, 10).entries();
        assertThat(entries.getFirst().message()).hasSize(SystemLogBuffer.MAX_LINE_BYTES);
        assertThat(entries.getFirst().truncated()).isTrue();
        assertThat(entries.getLast().message()).isEqualTo("healthy");
        assertThat(entries.getLast().truncated()).isFalse();
        assertThat(original.toByteArray()).isEqualTo(bytes);
        output.close();
        output.write('!');
        assertThat(original.toByteArray()[bytes.length]).isEqualTo((byte) '!');
    }

    @Test void installingCaptureRestoresBothJvmStreams() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        var logs = new SystemLogBuffer(Clock.systemUTC());
        try (var capture = SystemLogCapture.install(logs)) {
            assertThat(System.out).isNotSameAs(out);
            System.out.println("stdout fixture");
            System.err.println("stderr fixture");
        }
        assertThat(System.out).isSameAs(out);
        assertThat(System.err).isSameAs(err);
        assertThat(logs.snapshot(null, null, 10).entries()).extracting(SystemLogBuffer.Entry::stream)
                .containsExactly(SystemLogBuffer.Stream.STDOUT, SystemLogBuffer.Stream.STDERR);
    }
}
