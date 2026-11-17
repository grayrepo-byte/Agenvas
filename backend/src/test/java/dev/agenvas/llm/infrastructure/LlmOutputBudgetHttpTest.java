package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.llm.application.*;
import dev.agenvas.settings.application.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.ObjectMapper;

class LlmOutputBudgetHttpTest {
    @Test
    void legacyDiagnosticUsesGenericCapacityWithoutRequiringAConfigurationResave() {
        var cipher = mock(CredentialCipher.class);
        when(cipher.decrypt(any(), anyInt(), any())).thenReturn("synthetic-key");
        var config = new LlmProviderConfig(UUID.randomUUID(), 1, "http://127.0.0.1:1/v1", "synthetic",
                new byte[]{1}, new byte[]{2}, 1, "masked", false, true, Instant.EPOCH);
        var factory = new StoredChatModelFactory(cipher, new LlmEndpointPolicy());
        assertThatCode(() -> new StoredLlmDiagnosticGateway(factory).open(config)).doesNotThrowAnyException();
        assertThat(config.modelPolicy()).isNull();
    }

    @Test
    void unloadedSmallerOrReplacedOllamaRuntimeCannotReachGeneration() {
        var cipher = mock(CredentialCipher.class);
        when(cipher.decrypt(any(), anyInt(), any())).thenReturn("synthetic-key");
        var metadata = mock(dev.agenvas.settings.infrastructure.ModelMetadataClient.class);
        var policy = LlmModelPolicy.automatic(ModelCatalog.Provider.OLLAMA,
                new ModelCatalog.Model("synthetic", 32768, null, null, 8192, "original-digest", null),
                1024, LlmModelPolicy.OutputParameter.MAX_TOKENS);
        var config = new LlmProviderConfig(UUID.randomUUID(), 1, "http://127.0.0.1:1/v1", "synthetic",
                new byte[]{1}, new byte[]{2}, 1, "masked", true, true, Instant.EPOCH, policy);
        var factory = new StoredChatModelFactory(cipher, new LlmEndpointPolicy(), null, null, metadata);
        var budget = LlmRequestBudget.resolve(DirectorSettings.defaults(), policy);
        for (var observed : List.of(
                new ModelCatalog.Model("synthetic", 32768, null, null, null, "original-digest", null),
                new ModelCatalog.Model("synthetic", 32768, null, null, 4096, "original-digest", null),
                new ModelCatalog.Model("synthetic", 32768, null, null, 8192, "different-digest", null))) {
            when(metadata.fetch(ModelCatalog.Provider.OLLAMA, config.endpoint(), "synthetic-key", "synthetic"))
                    .thenReturn(new ModelCatalog(List.of(observed), false));
            assertThatThrownBy(() -> factory.create(config, budget)).isInstanceOf(LlmRequestBudget.Failure.class)
                    .hasMessage("MODEL_RUNTIME_CONTEXT_CHANGED");
        }
        var busy = mock(dev.agenvas.settings.infrastructure.ModelMetadataClient.MetadataFailure.class);
        when(busy.getMessage()).thenReturn("MODEL_METADATA_BUSY");
        when(metadata.fetch(ModelCatalog.Provider.OLLAMA, config.endpoint(), "synthetic-key", "synthetic"))
                .thenThrow(busy);
        assertThatThrownBy(() -> factory.create(config, budget)).isInstanceOf(java.io.UncheckedIOException.class)
                .hasMessageNotContaining("MODEL_RUNTIME_CONTEXT_CHANGED");
        // These failures occur before any generation dispatch.
    }

    @ParameterizedTest
    @EnumSource(LlmModelPolicy.OutputParameter.class)
    @NullSource
    void frozenOutputLimitIsSentAsExactlyOneSupportedParameter(LlmModelPolicy.OutputParameter parameter)
            throws Exception {
        var body = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = """
                    {"id":"synthetic-response","object":"chat.completion","created":1,"model":"synthetic",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            var cipher = mock(CredentialCipher.class);
            when(cipher.decrypt(any(), anyInt(), any())).thenReturn("synthetic-key");
            var policy = parameter == null
                    ? null
                    : LlmModelPolicy.manual(ModelCatalog.Provider.COMPATIBLE, 4096, null, 1024, parameter);
            var config = new LlmProviderConfig(UUID.randomUUID(), 1,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "synthetic", new byte[]{1}, new byte[]{2}, 1, "masked", true, true, Instant.EPOCH, policy);
            var factory = new StoredChatModelFactory(cipher, new LlmEndpointPolicy());
            var budget = LlmRequestBudget.resolve(DirectorSettings.defaults(), policy);
            factory.create(config, budget).call(List.of(new UserMessage("small synthetic request")), List.of(),
                    Map.of());
            var sent = new ObjectMapper().readTree(body.get());
            String field = parameter == LlmModelPolicy.OutputParameter.MAX_COMPLETION_TOKENS
                    ? "max_completion_tokens"
                    : "max_tokens";
            String other = parameter == LlmModelPolicy.OutputParameter.MAX_COMPLETION_TOKENS
                    ? "max_tokens"
                    : "max_completion_tokens";
            assertThat(sent.path(field).asInt()).isEqualTo(parameter == null ? 4096 : 1024);
            assertThat(sent.has(other)).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
