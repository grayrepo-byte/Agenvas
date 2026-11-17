package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.domain.AudioGenerationParameters;
import dev.agenvas.provider.domain.GoogleVideoModels;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.SeedreamModels;
import dev.agenvas.provider.domain.WanModels;
import dev.agenvas.shared.http.HttpTimeouts;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

/** Real loopback sockets and synthetic media exercise transport copies without a paid Provider call. */
class NativeMediaHttpTimeoutsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpTimeouts SHORT = new HttpTimeouts(1, 1, 1, 1);
    private static final HttpTimeouts LONG = new HttpTimeouts(1, 4, 4, 4);
    private static final byte[] MP4 = {0, 0, 0, 12, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

    @ParameterizedTest(name = "{0}")
    @MethodSource("apiCases")
    void concurrentCapabilityPoliciesBoundActualCreatesAndDoNotMutateSingleton(ApiCase api) throws Exception {
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor();
                var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var count = new AtomicInteger();
            var delay = new AtomicInteger(1_500);
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                count.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                pause(delay.get());
                byte[] bytes = api.response().getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException timedOutPeer) {
                    // The short call intentionally closes its socket before the synthetic acknowledgement.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                var invoke = api.factory().apply(origin(server));
                // Both copies come from one injected client and retain its local test routing.
                Runnable shortCall = invoke.apply(SHORT), longCall = invoke.apply(LONG);
                var failed = calls.submit(() -> catchThrowable(shortCall::run));
                var succeeded = calls.submit(longCall);
                assertThat(failed.get(6, TimeUnit.SECONDS)).isInstanceOf(api.unknown());
                succeeded.get(6, TimeUnit.SECONDS);
                delay.set(0);
                invoke.apply(null).run();
                assertThat(count).hasValue(3); // A timed-out paid create is never retried.
            } finally {
                server.stop(0);
            }
        }
    }

    static Stream<ApiCase> apiCases() throws IOException {
        var seedreamProfile = new SeedreamModels().resolve(SeedreamModels.PRO, SeedreamModels.GENERATE);
        var picture = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB), "jpeg", picture);
        var seedreamResponse = MAPPER.createObjectNode().put("model", seedreamProfile.model()).put("created", 1);
        seedreamResponse.putArray("data").addObject().put("size", "2048x2048")
                .put("b64_json", Base64.getEncoder().encodeToString(picture.toByteArray()))
                .put("output_format", "jpeg");
        return Stream.of(
                new ApiCase("Seedream", seedreamResponse.toString(), origin -> {
                    var base = new SeedreamClient(MAPPER, origin);
                    var values = MAPPER.createObjectNode().put("prompt", "Synthetic timeout test")
                            .put("size", "2048x2048");
                    var body = base.build(seedreamProfile, values, Map.of());
                    return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                            .create(seedreamProfile, values, null, "synthetic-key", body, "local-request")
                            .manifest().results()).hasSize(1);
                }, SeedreamClient.Uncertain.class),
                new ApiCase("Google video", "{\"name\":\"operations/original\"}", origin -> {
                    var base = new GoogleVideoClient(MAPPER, origin);
                    var models = new GoogleVideoModels();
                    var profile = models.profiles().stream().filter(p -> GoogleVideoModels.veo(p)
                            && "GENERATE".equals(p.operation())).findFirst().orElseThrow();
                    var values = NativeMediaProfiles.effectiveValues(new NativeMediaProfiles.Selection(models, profile),
                            MAPPER.createObjectNode(), "Synthetic timeout test", 8, MAPPER);
                    var body = base.build(profile, values, Map.of());
                    return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                            .create(profile, null, "synthetic-key", body)).isEqualTo("operations/original");
                }, GoogleVideoClient.Unknown.class),
                new ApiCase("Wan", "{\"output\":{\"task_id\":\"original\",\"task_status\":\"PENDING\"}}", origin -> {
                    var base = new WanClient(MAPPER, origin);
                    var models = new WanModels();
                    var profile = models.profiles().stream().filter(p -> "wan2.7-image".equals(p.model())
                            && "GENERATE".equals(p.operation())).findFirst().orElseThrow();
                    var values = NativeMediaProfiles.effectiveValues(new NativeMediaProfiles.Selection(models, profile),
                            MAPPER.createObjectNode(), "Synthetic timeout test", 5, MAPPER);
                    var body = base.build(profile, values, Map.of());
                    return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                            .create(profile, "https://synthetic.example", "synthetic-key", body)).isEqualTo("original");
                }, WanClient.Uncertain.class),
                new ApiCase("Kling legacy",
                        "{\"code\":0,\"data\":{\"task_id\":\"original\",\"task_status\":\"submitted\"}}", origin -> {
                            var base = new KlingClient(MAPPER, origin);
                            var body = base.prepareImage("Synthetic timeout test", "1:1", "1K", List.of());
                            return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                                    .createImage(null, "synthetic-key", body)).isEqualTo("original");
                        }, KlingClient.Uncertain.class),
                new ApiCase("Kling native",
                        "{\"code\":0,\"data\":{\"task_id\":\"original\",\"task_status\":\"submitted\"}}", origin -> {
                            var base = new KlingClient(MAPPER, origin);
                            var profile = KlingModels.resolve("kling-v3", "GENERATE", Task.Kind.IMAGE_GENERATION);
                            var values = KlingModels.effectiveValues(profile, MAPPER.createObjectNode(), "Synthetic",
                                    null,
                                    MAPPER);
                            var body = base.build(profile, values, Map.of(), "local-request");
                            return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                                    .create(profile, null, "synthetic-key", body)).isEqualTo("original");
                        }, KlingClient.Uncertain.class),
                new ApiCase("MiniMax", "{\"task_id\":\"original\"}", origin -> {
                    var base = new MiniMaxH3Client(MAPPER, origin);
                    var body = base.prepare("Synthetic timeout test", List.of(), 5, "16:9", "768p");
                    return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                            .create(null, "synthetic-key", body)).isEqualTo("original");
                }, MiniMaxH3Client.Uncertain.class),
                new ApiCase("Seedance", "{\"id\":\"original\"}", origin -> {
                    var base = new ArkSeedanceClient(MAPPER, origin);
                    return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                            .create("synthetic-key", "Synthetic timeout test", new byte[]{1, 2, 3}, 5, "16:9"))
                            .isEqualTo("original");
                }, ArkSeedanceClient.Uncertain.class),
                new ApiCase("Seed Audio", "{\"audio\":\"AQID\"}", origin -> {
                    var base = new SeedAudioClient(MAPPER, origin);
                    return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                            .synthesize("synthetic-key", "local-request", "Synthetic timeout test",
                                    AudioGenerationParameters.parse(null), List.of()))
                            .isEqualTo(new byte[]{1, 2, 3});
                }, SeedAudioClient.Uncertain.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("downloadCases")
    void resultDownloadsUseIsolatedPoliciesAndKeepCredentialsOffObjectRequests(DownloadCase download)
            throws Exception {
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor();
                var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var headers = new CopyOnWriteArrayList<String>();
            var delay = new AtomicInteger(1_500);
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                String googleKey = exchange.getRequestHeaders().getFirst("x-goog-api-key");
                if (exchange.getRequestURI().getPath().startsWith("/v1beta/")) {
                    headers.add("api:" + googleKey + ":" + authorization);
                    exchange.getResponseHeaders().set("Location", origin(server) + "/object.mp4");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                }
                headers.add("object:" + googleKey + ":" + authorization);
                pause(delay.get());
                try {
                    exchange.getResponseHeaders().set("Content-Type", "video/mp4");
                    exchange.sendResponseHeaders(200, MP4.length);
                    exchange.getResponseBody().write(MP4);
                } catch (IOException timedOutPeer) {
                    // Expected for the deliberately timed-out download.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                var invoke = download.factory().apply(origin(server));
                var failed = calls.submit(() -> catchThrowable(invoke.apply(SHORT)::run));
                var succeeded = calls.submit(invoke.apply(LONG));
                assertThat(failed.get(6, TimeUnit.SECONDS)).isInstanceOf(download.failure());
                succeeded.get(6, TimeUnit.SECONDS);
                delay.set(0);
                invoke.apply(null).run();
                assertThat(headers.stream().filter(h -> h.startsWith("object:")))
                        .containsExactly("object:null:null", "object:null:null", "object:null:null");
                assertThat(headers.stream().filter(h -> h.startsWith("api:")))
                        .allMatch(h -> h.equals("api:synthetic-key:null"));
            } finally {
                server.stop(0);
            }
        }
    }

    static Stream<DownloadCase> downloadCases() {
        return Stream.of(
                new DownloadCase("Ark result", origin -> {
                    var base = new ArkMediaDownloadPolicy();
                    return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                            .download(origin.resolve("/object.mp4"), new ByteArrayOutputStream(), 1024))
                            .isEqualTo(MP4.length);
                }, ArkMediaDownloadPolicy.TechnicalFailure.class),
                new DownloadCase("Kling result", origin -> {
                    var base = new KlingMediaDownloadPolicy();
                    return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                            .download(origin.resolve("/object.mp4"), new ByteArrayOutputStream(), true, 1024))
                            .isEqualTo("video/mp4");
                }, KlingMediaDownloadPolicy.TechnicalFailure.class),
                new DownloadCase("MiniMax result", origin -> {
                    var base = new MiniMaxMediaDownloadPolicy();
                    return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                            .download(origin.resolve("/object.mp4"), new ByteArrayOutputStream(), 1024))
                            .isEqualTo(MP4.length);
                }, MiniMaxMediaDownloadPolicy.TechnicalFailure.class),
                new DownloadCase("Wan result", origin -> {
                    var base = new WanMediaDownloadPolicy();
                    return timeouts -> () -> assertThat((timeouts == null ? base : base.withTimeouts(timeouts))
                            .download(origin.resolve("/object.mp4"), new ByteArrayOutputStream(), true))
                            .isEqualTo("video/mp4");
                }, WanMediaDownloadPolicy.TechnicalFailure.class),
                new DownloadCase("Google Files redirect", origin -> {
                    var base = new GoogleVideoClient(MAPPER, origin);
                    return timeouts -> () -> {
                        var sink = new ByteArrayOutputStream();
                        (timeouts == null ? base : base.withTimeouts(timeouts))
                                .download(null, "synthetic-key", "files/original", sink);
                        assertThat(sink.toByteArray()).isEqualTo(MP4);
                    };
                }, GoogleVideoClient.TechnicalFailure.class));
    }

    private static URI origin(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void pause(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private record ApiCase(String name, String response, Function<URI, Function<HttpTimeouts, Runnable>> factory,
            Class<? extends RuntimeException> unknown) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record DownloadCase(String name, Function<URI, Function<HttpTimeouts, Runnable>> factory,
            Class<? extends RuntimeException> failure) {
        @Override
        public String toString() {
            return name;
        }
    }
}
