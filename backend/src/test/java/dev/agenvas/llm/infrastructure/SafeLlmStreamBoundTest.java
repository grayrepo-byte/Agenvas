package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.junit.jupiter.api.Test;

/** Synthetic sources exercise byte accounting and eager close without a network listener. */
class SafeLlmStreamBoundTest {
    private static final int READ_CHUNK_BYTES = 8192;
    @Test
    void unknownLengthAndOversizedSingleLineStopAtTheByteBoundaryAndCloseTheSource() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicLong readBytes = new AtomicLong();
        Source endless = new Source() {
            @Override public long read(Buffer sink, long byteCount) {
                int size = (int) Math.min(byteCount, READ_CHUNK_BYTES);
                sink.write(new byte[size]);
                readBytes.addAndGet(size);
                return size;
            }
            @Override public Timeout timeout() { return Timeout.NONE; }
            @Override public void close() { closed.set(true); }
        };
        Response response = SafeLlmTransport.boundStream(response(endless, -1));
        assertThatThrownBy(() -> response.body().source().readUtf8LineStrict())
                .isInstanceOf(IOException.class).hasMessageContaining("transport size limit");
        assertThat(closed).isTrue();
        assertThat(readBytes.get()).isLessThanOrEqualTo(SafeLlmTransport.MAX_STREAM_RESPONSE_BYTES + READ_CHUNK_BYTES);
    }

    @Test
    void knownOversizedLengthIsRejectedBeforeReadingAndStillClosesTheBody() {
        AtomicBoolean closed = new AtomicBoolean();
        Source unread = new Source() {
            @Override public long read(Buffer sink, long byteCount) { throw new AssertionError("Body must not be read"); }
            @Override public Timeout timeout() { return Timeout.NONE; }
            @Override public void close() { closed.set(true); }
        };
        assertThatThrownBy(() -> SafeLlmTransport.boundStream(response(unread, SafeLlmTransport.MAX_STREAM_RESPONSE_BYTES + 1)))
                .isInstanceOf(IOException.class).hasMessageContaining("transport size limit");
        assertThat(closed).isTrue();
    }

    @Test
    void smallStreamPreservesItsContentTypeAndExplicitCloseClosesTheOriginalSource() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        Buffer data = new Buffer().writeUtf8("data: [DONE]\n\n");
        Source source = new Source() {
            @Override public long read(Buffer sink, long byteCount) { return data.read(sink, byteCount); }
            @Override public Timeout timeout() { return Timeout.NONE; }
            @Override public void close() { closed.set(true); }
        };
        try (Response response = SafeLlmTransport.boundStream(response(source, -1))) {
            assertThat(response.body().contentType().toString()).isEqualTo("text/event-stream");
            assertThat(response.body().string()).isEqualTo("data: [DONE]\n\n");
        }
        assertThat(closed).isTrue();
    }

    private Response response(Source source, long length) {
        BufferedSource buffered = Okio.buffer(source);
        return new Response.Builder().request(new Request.Builder().url("https://synthetic.example/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(new ResponseBody() {
                    @Override public MediaType contentType() { return MediaType.get("text/event-stream"); }
                    @Override public long contentLength() { return length; }
                    @Override public BufferedSource source() { return buffered; }
                }).build();
    }
}
