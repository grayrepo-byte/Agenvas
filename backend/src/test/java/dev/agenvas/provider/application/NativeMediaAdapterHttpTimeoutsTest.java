package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.infrastructure.ArkMediaDownloadPolicy;
import dev.agenvas.provider.infrastructure.ArkSeedanceClient;
import dev.agenvas.provider.infrastructure.GoogleVideoClient;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.KlingClient;
import dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy;
import dev.agenvas.provider.infrastructure.MiniMaxH3Client;
import dev.agenvas.provider.infrastructure.MiniMaxMediaDownloadPolicy;
import dev.agenvas.provider.infrastructure.WanClient;
import dev.agenvas.provider.infrastructure.WanMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Frozen capability versions reach real HTTP polling and archival, including legacy Kling recovery. */
class NativeMediaAdapterHttpTimeoutsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
    private static final byte[] MP4 = {0, 0, 0, 12, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

    @ParameterizedTest(name = "{0}, downloads={1}")
    @MethodSource("operations")
    void frozenVersionsControlPollingAndArchivalWithoutChangingSharedClient(Protocol protocol, boolean completed)
            throws Exception {
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor();
                var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var methods = new CopyOnWriteArrayList<String>();
            var delay = new AtomicInteger(1_500);
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                methods.add(exchange.getRequestMethod());
                String path = exchange.getRequestURI().getPath();
                boolean object = path.startsWith("/object.");
                if (path.endsWith(":download")) {
                    exchange.getResponseHeaders().set("Location", origin + "/object.mp4");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                }
                if (completed ? object : !path.startsWith("/object."))
                    pause(delay.get());
                byte[] bytes = object
                        ? (protocol.image() ? PNG : MP4)
                        : response(protocol, origin, completed, path).getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", object
                            ? protocol.image() ? "image/png" : "video/mp4"
                            : "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException timedOutPeer) {
                    // The short capability deliberately disconnects during this response.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                Fixture fixture = fixture(protocol, origin);
                var failed = calls.submit(() -> catchThrowable(() -> invoke(fixture, fixture.shorter(), completed)));
                var succeeded = calls.submit(() -> invoke(fixture, fixture.longer(), completed));
                assertThat(failed.get(6, TimeUnit.SECONDS)).isInstanceOf(failure(protocol, completed));
                succeeded.get(6, TimeUnit.SECONDS);
                delay.set(0);
                invoke(fixture, fixture.legacy(), completed);
                assertThat(methods).isNotEmpty().allMatch("GET"::equals);
            } finally {
                server.stop(0);
            }
        }
    }

    static Stream<Arguments> operations() {
        return Stream.of(Protocol.values()).flatMap(p -> Stream.of(Arguments.of(p, false), Arguments.of(p, true)));
    }

    private static void invoke(Fixture fixture, AttemptContext context, boolean completed) {
        Submission result = fixture.adapter().reconcile(context);
        if (!completed) {
            assertThat(result).isInstanceOf(Submission.Pending.class);
            return;
        }
        if (result instanceof Submission.Completed media) {
            try (var payload = media.payload()) {
                assertThat(payload.stream().readAllBytes()).isNotEmpty();
            } catch (IOException unavailable) {
                throw new AssertionError(unavailable);
            }
        } else {
            assertThat(result).isInstanceOf(Submission.CompletedResults.class);
            var manifest = ((Submission.CompletedResults) result).manifest();
            try (var payload = fixture.adapter().downloadResult(context, manifest.results().getFirst())) {
                assertThat(payload.stream().readAllBytes()).isEqualTo(PNG);
            } catch (IOException unavailable) {
                throw new AssertionError(unavailable);
            }
        }
    }

    private static Fixture fixture(Protocol protocol, URI origin) {
        var catalog = mock(JooqMediaCapabilityRepository.class);
        var cipher = mock(CredentialCipher.class);
        var tools = mock(MediaToolRunner.class);
        when(cipher.decryptMedia(any(UUID.class), anyInt(), any())).thenReturn("synthetic-key");
        when(tools.ffprobe(any())).thenReturn("{\"format\":{\"format_name\":\"mp4\",\"duration\":\"8\"},"
                + "\"streams\":[{\"codec_type\":\"video\",\"width\":1280,\"height\":720},{\"codec_type\":\"audio\"}]}");
        MediaAdapter adapter = switch (protocol) {
            case SEEDANCE -> new ArkSeedance2Adapter(catalog, cipher, null, null, null,
                    new ArkSeedanceClient(MAPPER, origin), new ArkMediaDownloadPolicy(), tools, MAPPER, null, null);
            case MINIMAX -> new MiniMaxH3Adapter(catalog, cipher, new MiniMaxH3Client(MAPPER, origin),
                    new MiniMaxMediaDownloadPolicy(), tools, MAPPER, null);
            case KLING_LEGACY -> new KlingImage3Adapter(catalog, cipher, new KlingClient(MAPPER, origin),
                    new KlingMediaDownloadPolicy(), MAPPER, null);
            case KLING_NATIVE -> new KlingImageAdapter(catalog, cipher, new KlingClient(MAPPER, origin),
                    new KlingMediaDownloadPolicy(), MAPPER, null);
            case WAN -> new WanImageAdapter(catalog, cipher, new WanClient(MAPPER, origin),
                    new WanMediaDownloadPolicy(), MAPPER, null);
            case GOOGLE -> new GoogleVeoAdapter(catalog, cipher, new GoogleVideoClient(MAPPER, origin), null,
                    tools, MAPPER);
        };
        var settings = MAPPER.createObjectNode();
        var input = MAPPER.createObjectNode().put("durationSeconds", 8);
        if (protocol == Protocol.KLING_NATIVE) {
            var p = KlingModels.resolve("kling-v3", "GENERATE", Task.Kind.IMAGE_GENERATION);
            settings.put("model", p.model()).put("operation", p.operation().name());
            var values = KlingModels.effectiveValues(p, MAPPER.createObjectNode(), "Synthetic", null, MAPPER);
            input.putObject("mediaInput").putObject("parameters").set("dynamicValues", values.deepCopy());
            ((ObjectNode) input.path("mediaInput")).putObject("providerParameters").putObject("kling")
                    .put("model", p.model()).put("operation", p.operation().name()).set("values", values);
        } else if (protocol == Protocol.WAN || protocol == Protocol.GOOGLE) {
            settings.put("model", protocol == Protocol.WAN ? "wan2.7-image" : "veo-3.1-generate-preview")
                    .put("operation", "GENERATE");
            var selected = NativeMediaProfiles.resolve(adapter.adapterId(), settings);
            var values = NativeMediaProfiles.effectiveValues(selected, MAPPER.createObjectNode(), "Synthetic", null,
                    MAPPER);
            input.putObject("mediaInput").putObject("parameters").set("dynamicValues", values.deepCopy());
            ((ObjectNode) input.path("mediaInput")).putObject("providerParameters").putObject("native")
                    .put("adapterId", adapter.adapterId()).put("model", selected.profile().model())
                    .put("operation", "GENERATE").set("values", values);
        }
        var task = mock(Task.class);
        when(task.input()).thenReturn(input);
        when(task.kind()).thenReturn(protocol.image() ? Task.Kind.IMAGE_GENERATION : Task.Kind.VIDEO_GENERATION);
        UUID capability = UUID.randomUUID(), connection = UUID.randomUUID(), owner = UUID.randomUUID();
        var connectionVersion = new JooqMediaCapabilityRepository.ConnectionVersion(connection, 1, origin.toString(),
                null, new byte[]{1}, new byte[]{2}, 1, "masked");
        var contexts = new java.util.ArrayList<AttemptContext>();
        for (int version = 1; version <= 3; version++) {
            var configured = settings.deepCopy();
            if (version < 3) {
                int seconds = version == 1 ? 1 : 4;
                configured.putObject("httpTimeouts").put("connectSeconds", 1).put("writeSeconds", seconds)
                        .put("readSeconds", seconds).put("callSeconds", seconds);
            }
            var binding = new MediaCapabilityBinding(connection, 1, capability, version, adapter.adapterId(),
                    "a".repeat(64));
            var snapshot = new JooqMediaCapabilityRepository.Snapshot(null, null, connectionVersion,
                    adapter.adapterId(), binding.mappingSha256(), MAPPER.createObjectNode()
                            .set("settings", configured).toString());
            when(catalog.snapshotAt(capability, version, connection, 1)).thenReturn(Optional.of(snapshot));
            contexts.add(new AttemptContext(task, binding, owner, "local-request",
                    protocol == Protocol.GOOGLE ? "operations/original" : "original"));
        }
        return new Fixture(adapter, contexts.get(0), contexts.get(1), contexts.get(2));
    }

    private static String response(Protocol protocol, URI origin, boolean completed, String path) {
        String url = origin + (protocol.image() ? "/object.png" : "/object.mp4");
        return switch (protocol) {
            case SEEDANCE -> "{\"id\":\"original\",\"model\":\"" + ArkSeedanceClient.MODEL_ID
                    + "\",\"status\":\"" + (completed ? "succeeded" : "running")
                    + "\",\"content\":{\"video_url\":\"" + url + "\"}}";
            case MINIMAX -> "{\"task\":{\"id\":\"original\",\"model\":\"MiniMax-H3\",\"status\":\""
                    + (completed ? "succeeded" : "running") + "\",\"content\":{\"url\":\"" + url + "\"}}}";
            case KLING_LEGACY, KLING_NATIVE -> "{\"code\":0,\"data\":{\"task_id\":\"original\",\"task_status\":\""
                    + (completed ? "succeed" : "processing") + "\",\"task_result\":{\"images\":[{\"index\":1,\"url\":\""
                    + url + "\"}]}}}";
            case WAN -> "{\"output\":{\"task_id\":\"original\",\"task_status\":\""
                    + (completed ? "SUCCEEDED" : "RUNNING") + "\",\"finished\":true,\"choices\":[{"
                    + "\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"image\",\"image\":\""
                    + url + "\"}]}}]}}";
            case GOOGLE -> path.startsWith("/v1beta/files/")
                    ? "{\"name\":\"files/original\",\"mimeType\":\"video/mp4\",\"state\":\"ACTIVE\"}"
                    : completed
                            ? "{\"name\":\"operations/original\",\"done\":true,\"response\":{\"generateVideoResponse\":{"
                                    + "\"generatedSamples\":[{\"video\":{\"uri\":\"https://generativelanguage.googleapis.com/v1beta/files/original\"}}]}}}"
                            : "{\"name\":\"operations/original\"}";
        };
    }

    private static Class<? extends RuntimeException> failure(Protocol protocol, boolean completed) {
        return switch (protocol) {
            case SEEDANCE -> completed
                    ? ArkMediaDownloadPolicy.TechnicalFailure.class
                    : ArkSeedanceClient.TechnicalFailure.class;
            case MINIMAX -> completed
                    ? MiniMaxMediaDownloadPolicy.TechnicalFailure.class
                    : MiniMaxH3Client.TechnicalFailure.class;
            case KLING_LEGACY, KLING_NATIVE -> completed
                    ? KlingMediaDownloadPolicy.TechnicalFailure.class
                    : KlingClient.TechnicalFailure.class;
            case WAN -> completed ? WanMediaDownloadPolicy.TechnicalFailure.class : WanClient.TechnicalFailure.class;
            case GOOGLE -> GoogleVideoClient.TechnicalFailure.class;
        };
    }

    private static void pause(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private enum Protocol {
        SEEDANCE, MINIMAX, KLING_LEGACY, KLING_NATIVE, WAN, GOOGLE;

        boolean image() {
            return this == KLING_LEGACY || this == KLING_NATIVE || this == WAN;
        }
    }

    private record Fixture(MediaAdapter adapter, AttemptContext shorter, AttemptContext longer, AttemptContext legacy) {
    }
}
