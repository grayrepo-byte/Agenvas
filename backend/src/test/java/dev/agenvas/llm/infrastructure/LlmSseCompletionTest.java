package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.llm.application.LlmStreamIncompleteException;
import java.nio.charset.StandardCharsets;
import okio.Buffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Framing and sentinel checks are independent of network chunk boundaries and debug logging. */
class LlmSseCompletionTest {
    @Test
    void actualSseDetectionDoesNotTurnOrdinaryJsonIntoAnIncompleteStream() throws Exception {
        var json = new LlmSseCompletion(false);
        json.accept(new Buffer().writeUtf8("{\"data\":\"not an SSE field\"}"));
        assertThatCode(json::end).doesNotThrowAnyException();
        var data = new LlmSseCompletion(false);
        data.accept(new Buffer().writeUtf8("data: {\"choices\":[]}"));
        assertThatThrownBy(data::end).isInstanceOf(LlmStreamIncompleteException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "data: [DONE]\n\n",
            "\uFEFF: keepalive\r\ndata: [DONE]\r\n\r\n",
            "data: {\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\"}]}\r\r",
            "data: {\"choices\":[\r\ndata: {\"index\":0,\"finish_reason\":\"length\"}]}\r\n\r\n",
            "data: {\"choices\":[{\"index\":0,\"finish_reason\":\"provider_end\"}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好🌊\"},\"finish_reason\":\"STOP\"}]}\n\n"
    })
    void recognizesOnlyDispatchedCompletionFramesAcrossSingleByteReads(String stream) throws Exception {
        var observer = observer(stream);
        assertThatCode(observer::end).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", ": keepalive\n\n", "data: [DONE]\n", "data: [DONE]",
            "\uFEFFdata: [DONE]\n\n",
            "\uFEFFdata: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}",
            "data: {\"choices\":[{\"index\":1,\"finish_reason\":\"stop\"}]}\n\n",
            "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n",
            "data: {\"choices\":[{\"index\":18446744073709551616,\"finish_reason\":\"stop\"}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"finish_reason\":null}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"finish_reason\":\"\"}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"[DONE]\",\"finish_reason\":\"stop\"}}]}\n\n",
            "data: [DONE]\ndata: extra\n\n",
            "data: [DONE]junk\n\ndata: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"finish_reason\\\":\\\"stop\\\"}\"}}]}}]}\n\n"
    })
    void cleanEofWithoutASelectedCompletionSignalRemainsIncomplete(String stream) throws Exception {
        var observer = observer(stream);
        assertThatThrownBy(observer::end).isInstanceOf(LlmStreamIncompleteException.class)
                .hasMessage("Model stream ended without a completion signal");
    }

    private LlmSseCompletion observer(String stream) throws Exception {
        var observer = new LlmSseCompletion();
        for (byte value : stream.getBytes(StandardCharsets.UTF_8)) {
            observer.accept(new Buffer().writeByte(value));
        }
        return observer;
    }
}
