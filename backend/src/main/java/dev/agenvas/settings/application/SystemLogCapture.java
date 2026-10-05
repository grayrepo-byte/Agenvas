package dev.agenvas.settings.application;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Tees Java stdout/stderr before Spring initializes logging, preserving original console bytes. */
public final class SystemLogCapture implements AutoCloseable {
    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final PrintStream capturedOut;
    private final PrintStream capturedErr;

    private SystemLogCapture(SystemLogBuffer buffer) {
        originalOut = System.out;
        originalErr = System.err;
        capturedOut = new PrintStream(new LineOutput(originalOut, buffer, SystemLogBuffer.Stream.STDOUT),
                true, StandardCharsets.UTF_8);
        capturedErr = new PrintStream(new LineOutput(originalErr, buffer, SystemLogBuffer.Stream.STDERR),
                true, StandardCharsets.UTF_8);
        System.setOut(capturedOut);
        System.setErr(capturedErr);
    }

    public static SystemLogCapture install(SystemLogBuffer buffer) { return new SystemLogCapture(buffer); }

    /** Restores only streams still owned by this capture, and never closes the original console. */
    @Override public void close() {
        capturedOut.flush();
        capturedErr.flush();
        if (System.out == capturedOut) System.setOut(originalOut);
        if (System.err == capturedErr) System.setErr(originalErr);
    }

    /** Buffer complete UTF-8 lines, including split writes; discard oversize tails until newline. */
    static final class LineOutput extends OutputStream {
        private final PrintStream destination;
        private final SystemLogBuffer buffer;
        private final SystemLogBuffer.Stream stream;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private boolean truncated;

        LineOutput(PrintStream destination, SystemLogBuffer buffer, SystemLogBuffer.Stream stream) {
            this.destination = destination;
            this.buffer = buffer;
            this.stream = stream;
        }

        @Override public synchronized void write(int value) {
            destination.write(value);
            capture(value & 0xff);
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            destination.write(bytes, offset, length);
            for (int index = offset; index < offset + length; index++) capture(bytes[index] & 0xff);
        }

        private void capture(int value) {
            if (value == '\n') {
                String line = pending.toString(StandardCharsets.UTF_8);
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                buffer.append(stream, line, truncated);
                pending.reset();
                truncated = false;
            } else if (pending.size() < SystemLogBuffer.MAX_LINE_BYTES) {
                pending.write(value);
            } else {
                truncated = true;
            }
        }

        // A flush can occur between bytes of a line or a credential: publish only at newline.
        @Override public synchronized void flush() { destination.flush(); }
        @Override public void close() { flush(); }
    }
}
