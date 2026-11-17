package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.shared.http.HttpTimeouts;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

/** Real loopback requests exercise the protocol entry points, never a billable Provider. */
class PublishedHttpTimeoutsTest {
    private enum Provider {
        OPENAI, GOOGLE_IMAGE, COMFYUI, AUTODL, RUNNINGHUB
    }
    private enum Download {
        COMFYUI, AUTODL, RUNNINGHUB
    }
    private static final HttpTimeouts SHORT = new HttpTimeouts(1, 1, 1, 3);
    private static final HttpTimeouts LONG = new HttpTimeouts(1, 2, 2, 3);
    private static final long SLOW_HEADERS_MS = 1_250;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService handlers;
    private String origin;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void eachProviderUsesPublishedReadLimitOnItsRealProtocolRequest(Provider provider) throws Exception {
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            slowResponse(exchange, "application/json", response(provider).getBytes(StandardCharsets.UTF_8));
        });

        assertThatThrownBy(() -> invoke(provider, SHORT)).isInstanceOf(RuntimeException.class);
        assertThat(requests).hasValue(1);
        invoke(provider, LONG);
        assertThat(requests).hasValue(2);
    }

    @ParameterizedTest
    @EnumSource(Download.class)
    void dedicatedResultDownloadsAlsoUsePublishedLimits(Download provider) throws Exception {
        byte[] result = new byte[64];
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            slowResponse(exchange, "video/mp4", result);
        });

        assertThatThrownBy(() -> download(provider, SHORT)).isInstanceOf(RuntimeException.class);
        assertThat(requests).hasValue(1);
        assertThat(download(provider, LONG)).containsExactly(result);
        assertThat(requests).hasValue(2);
    }

    @Test
    void runningHubDoesNotReplacePublishedWholeCallDeadlineWithItsStaticDefault() {
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"taskId\":\"synthetic-task\"}".getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, body.length);
                for (byte value : body) {
                    exchange.getResponseBody().write(value);
                    exchange.getResponseBody().flush();
                    Thread.sleep(100);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException expired) {
                // The client expires its whole-call budget while the server continues making progress.
            } finally {
                exchange.close();
            }
        });
        var client = new RunningHubClient(mapper).withTimeouts(new HttpTimeouts(1, 1, 1, 1));

        assertThatThrownBy(() -> client.submit(origin, "synthetic-key", definition(), mapper.createArrayNode()))
                .isInstanceOf(RunningHubClient.Uncertain.class);
        assertThat(requests).hasValue(1);
    }

    @Test
    void openAiDownloadRetriesDoNotResubmitGenerationAndKeepTheirPublishedLimits() {
        AtomicInteger downloads = new AtomicInteger();
        server.createContext("/v1/images/generations", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"data\":[{\"url\":\"" + origin + "/result.png\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/result.png", exchange -> {
            downloads.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            slowResponse(exchange, "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        });
        var client = new OpenAiImage2Client(mapper).withTimeouts(SHORT);

        assertThatThrownBy(() -> client.generate("synthetic-key", OpenAiImage2Client.DEFAULT_MODEL,
                "Synthetic image", "medium", "1024x1024", false, origin + "/v1"))
                .isInstanceOfSatisfying(OpenAiImage2Client.Uncertain.class,
                        failure -> assertThat(failure.reasonCode()).isEqualTo(ProviderFailureCodes.DOWNLOAD_FAILED));
        assertThat(requests).hasValue(1);
        assertThat(downloads).hasValue(3);
    }

    private void invoke(Provider provider, HttpTimeouts policy) throws Exception {
        switch (provider) {
            case OPENAI -> {
                try (var result = new OpenAiImage2Client(mapper).withTimeouts(policy)
                        .generate("synthetic-key", OpenAiImage2Client.DEFAULT_MODEL,
                                "Synthetic image", "medium", "1024x1024", false, origin + "/v1")) {
                    assertThat(result.stream().readAllBytes()).isNotEmpty();
                }
            }
            case GOOGLE_IMAGE -> {
                try (var result = new GoogleNanoBananaClient(mapper).withTimeouts(policy)
                        .generate("synthetic-key", "synthetic-model", origin + "/v1", "Synthetic image", "1:1",
                                "1K", List.of())) {
                    assertThat(result.stream().readAllBytes()).isNotEmpty();
                }
            }
            case COMFYUI -> assertThat(new ComfyUiClient(new ComfyUiProperties(origin), mapper).withTimeouts(policy)
                    .submit(mapper.createObjectNode(), UUID.randomUUID())).isEqualTo("synthetic-prompt");
            case AUTODL -> assertThat(new AutoDlClient(mapper, URI.create(origin)).withTimeouts(policy)
                    .create("synthetic-key", "synthetic-workflow", mapper.createObjectNode()))
                    .isEqualTo("synthetic-task");
            case RUNNINGHUB -> assertThat(new RunningHubClient(mapper).withTimeouts(policy)
                    .submit(origin, "synthetic-key", definition(), mapper.createArrayNode()))
                    .isEqualTo("synthetic-task");
        }
    }

    private byte[] download(Download provider, HttpTimeouts policy) throws IOException {
        return switch (provider) {
            case COMFYUI -> {
                try (var result = new ComfyUiClient(new ComfyUiProperties(origin), mapper).withTimeouts(policy)
                        .output("synthetic.png")) {
                    yield result.readAllBytes();
                }
            }
            case AUTODL -> {
                try (var result = new AutoDlClient(mapper, URI.create(origin)).withTimeouts(policy)
                        .downloadVideo(origin + "/comfyui/outputs/synthetic.mp4")) {
                    yield result.stream().readAllBytes();
                }
            }
            case RUNNINGHUB -> {
                try (var result = new RunningHubClient(mapper).withTimeouts(policy)
                        .download(origin, origin + "/synthetic.mp4")) {
                    yield result.stream().readAllBytes();
                }
            }
        };
    }

    private String response(Provider provider) {
        return switch (provider) {
            case OPENAI -> "{\"data\":[{\"b64_json\":\"iVBORw0KGgo=\"}]}";
            case GOOGLE_IMAGE -> "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{"
                    + "\"mimeType\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}]}}]}";
            case COMFYUI -> "{\"prompt_id\":\"synthetic-prompt\"}";
            case AUTODL -> "{\"code\":\"Success\",\"data\":{\"task_id\":\"synthetic-task\"}}";
            case RUNNINGHUB -> "{\"taskId\":\"synthetic-task\"}";
        };
    }

    private RunningHubDefinition definition() {
        return new RunningHubDefinition(1, "V2", RunningHubDefinition.TargetType.WORKFLOW, "123",
                List.of(), List.of(), List.of(), "default", false, false, null, null, null, null);
    }

    private void slowResponse(HttpExchange exchange, String type, byte[] body) {
        try {
            Thread.sleep(SLOW_HEADERS_MS);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException expired) {
            // Expected when the impatient invocation closes its loopback connection.
        } finally {
            exchange.close();
        }
    }
}
