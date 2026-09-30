package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.domain.AudioGenerationParameters;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Fake official HTTP protocol; never submits a paid generation. */
class SeedAudioClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test
    void sendsSeedAudioModelOrderedReferencesAndControlsWithSingleKeyAuthentication() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] firstAudio = {1, 2, 3};
        byte[] resultAudio = {4, 5, 6};
        server.createContext("/api/v3/tts/create", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Api-Key")).isEqualTo("fake-key");
            assertThat(exchange.getRequestHeaders().getFirst("X-Api-Request-Id")).isEqualTo("request-1");
            assertThat(exchange.getRequestHeaders().getFirst("X-Api-Resource-Id")).isNull();
            var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(body.path("model").asText()).isEqualTo("seed-audio-1.0");
            assertThat(body.path("text_prompt").asText()).isEqualTo("参考 @音频1 说你好");
            assertThat(body.at("/references/0/audio_data").asText()).isEqualTo(Base64.getEncoder().encodeToString(firstAudio));
            assertThat(body.at("/references/1/speaker").asText()).isEqualTo("zh_female_vv_uranus_bigtts");
            assertThat(body.at("/audio_config/speech_rate").asInt()).isEqualTo(-20);
            assertThat(body.at("/audio_config/loudness_rate").asInt()).isEqualTo(15);
            assertThat(body.at("/audio_config/pitch_rate").asInt()).isEqualTo(3);
            byte[] response = ("{\"audio\":\"" + Base64.getEncoder().encodeToString(resultAudio) + "\",\"original_duration\":3}").getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var client = new SeedAudioClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            assertThat(client.synthesize("fake-key", "request-1", "参考 @音频1 说你好",
                    new AudioGenerationParameters("zh_female_vv_uranus_bigtts", -20, 15, 3),
                    List.of(new SeedAudioClient.Reference("audio_data", firstAudio)))).isEqualTo(resultAudio);
        } finally { server.stop(0); }
    }
    @Test
    void lostResponseIsUnknownAndPostNeverRetries() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/api/v3/tts/create", exchange -> { requests.incrementAndGet(); exchange.close(); });
        server.start();
        try {
            var client = new SeedAudioClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            assertThatThrownBy(() -> client.synthesize("fake-key", "request-1", "Hello",
                    AudioGenerationParameters.parse(null), List.of())).isInstanceOf(SeedAudioClient.Uncertain.class);
            assertThat(requests).hasValue(1);
        } finally { server.stop(0); }
    }
    @Test
    void malformedSuccessAndRedirectNeverRetryOrFollowLocation() throws Exception {
        for (int status : List.of(200, 307)) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var requests = new AtomicInteger();
            var redirectedRequests = new AtomicInteger();
            server.createContext("/api/v3/tts/create", exchange -> {
                requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Location", "/redirected");
                byte[] response = "invalid-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
            });
            server.createContext("/redirected", exchange -> { redirectedRequests.incrementAndGet(); exchange.close(); });
            server.start();
            try {
                var client = new SeedAudioClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
                assertThatThrownBy(() -> client.synthesize("fake-key", "request-1", "Hello",
                        AudioGenerationParameters.parse(null), List.of())).isInstanceOf(SeedAudioClient.Uncertain.class);
                assertThat(requests).hasValue(1);
                assertThat(redirectedRequests).hasValue(0);
            } finally { server.stop(0); }
        }
    }
    @Test
    void rejectsCustomOriginsAndInvalidAudioControls() {
        assertThatThrownBy(() -> new SeedAudioClient(mapper, URI.create("https://evil.example")))
                .isInstanceOf(IllegalArgumentException.class);
        for (String json : List.of("{\"speechRate\":101}", "{\"pitchRate\":13}", "{\"loudnessRate\":-51}", "{\"speaker\":\"bad\\nspeaker\"}"))
            assertThatThrownBy(() -> AudioGenerationParameters.parse(mapper.readTree(json)))
                    .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        assertThat(AudioGenerationParameters.parse(mapper.readTree("{}" )).speaker()).isEmpty();
    }
}
