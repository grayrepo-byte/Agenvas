package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.LlmStreamIncompleteException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import okio.Buffer;
import tools.jackson.databind.ObjectMapper;

/** Observes bounded SSE frames without consuming or delaying the SDK's stream. */
final class LlmSseCompletion {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private final boolean declaredSse;
    private boolean afterCr;
    private boolean complete;
    private boolean dataLine;
    private boolean sdkStopped;
    private boolean sawDataField;

    LlmSseCompletion() { this(true); }

    LlmSseCompletion(boolean declaredSse) { this.declaredSse = declaredSse; }

    /** Total input is bounded by SafeLlmTransport before it reaches this observer. */
    void accept(Buffer bytes) throws IOException {
        while (!bytes.exhausted()) {
            int value = bytes.readByte() & 0xff;
            if (value == '\n' && afterCr) { afterCr = false; continue; }
            afterCr = value == '\r';
            if (value == '\r' || value == '\n') line();
            else line.write(value);
        }
    }

    private void line() {
        String value = line.toString(StandardCharsets.UTF_8);
        line.reset();
        // Match the installed SDK: a BOM-prefixed field is not dispatched as "data".
        // Stripping it only in this observer could bless a marker the SDK never consumed.
        if (value.isEmpty()) {
            frame();
            data.setLength(0);
            dataLine = false;
        } else if (value.equals("data") || value.startsWith("data:")) {
            sawDataField = true;
            if (dataLine) data.append('\n');
            dataLine = true;
            String content = value.equals("data") ? "" : value.substring(5);
            // SSE removes one optional ASCII space, not arbitrary Unicode whitespace.
            data.append(content.startsWith(" ") ? content.substring(1) : content);
        }
    }

    private void frame() {
        if (complete || sdkStopped || data.isEmpty()) return;
        String event = data.toString();
        // The SDK treats any [DONE] prefix as the end and ignores later events. Never let
        // an ignored tail supply a completion marker for an earlier incomplete tool call.
        if (event.startsWith("[DONE]")) {
            sdkStopped = true;
            if ("[DONE]".equals(event.strip())) complete = true;
            return;
        }
        try {
            var value = MAPPER.readTree(event);
            for (var choice : value.path("choices")) {
                var index = choice.path("index");
                var reason = choice.path("finish_reason");
                if (index.isIntegralNumber() && "0".equals(index.asText()) && reason.isString()
                        && !reason.asText().isBlank()) {
                    complete = true;
                    return;
                }
            }
        } catch (RuntimeException invalidEvent) {
            // The SDK owns protocol errors. Invalid or incomplete data cannot signal completion.
        }
    }

    void end() throws LlmStreamIncompleteException {
        // An unfinished final line can identify actual SSE, but cannot dispatch a frame.
        if (line.size() > 0) line();
        // SSE dispatches only at an empty line; EOF cannot complete an unfinished data frame.
        if ((declaredSse || sawDataField) && !complete) throw new LlmStreamIncompleteException();
    }
}
