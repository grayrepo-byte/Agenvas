package dev.agenvas.settings.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.settings.application.LlmEndpointProperties;
import dev.agenvas.settings.application.ModelCatalog.Provider;
import dev.agenvas.settings.application.ModelCatalog;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Dns;
import org.junit.jupiter.api.Test;

class ModelMetadataClientTest {
    @Test void echoedCredentialsInPaginationCursorsNeverEnterAFollowupUrl() throws Exception {
        for (var provider : List.of(Provider.GEMINI, Provider.ANTHROPIC)) {
            var calls = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/models", exchange -> {
                calls.incrementAndGet();
                byte[] body = (provider == Provider.GEMINI
                        ? "{\"models\":[{\"name\":\"models/model\"}],\"nextPageToken\":\"echo-synthetic-key\"}"
                        : "{\"data\":[{\"id\":\"model\"}],\"has_more\":true,\"last_id\":\"echo-synthetic-key\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            }); server.start();
            try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(2))) {
                var result = client.fetch(provider, endpoint(server)+"/v1", "synthetic-key", null);
                assertThat(result.partial()).isTrue();
                assertThat(calls).hasValue(1);
            } finally { server.stop(0); }
        }
    }

    @Test void metadataCannotEchoTheSubmittedCredentialBackAsAModelIdOrDigest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"echo-synthetic-key\"},{\"id\":\"safe-model\",\"digest\":\"echo-synthetic-key\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(2))) {
            var result = client.fetch(Provider.COMPATIBLE, endpoint(server)+"/v1", "synthetic-key", null);
            assertThat(result.models()).extracting(ModelCatalog.Model::id).containsExactly("safe-model");
            assertThat(result.models().getFirst().digest()).isNull();
            assertThat(result.partial()).isTrue();
        } finally { server.stop(0); }
    }

    @Test void temporaryHttpFailuresRemainUnavailableRatherThanRuntimeMismatches() throws Exception {
        var status = new AtomicInteger(429);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> { exchange.sendResponseHeaders(status.get(), -1); exchange.close(); });
        server.start();
        try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> client.fetch(Provider.OPENAI, endpoint(server)+"/v1", "synthetic-key", null))
                    .hasMessage("MODEL_METADATA_UNAVAILABLE");
            status.set(503);
            assertThatThrownBy(() -> client.fetch(Provider.OPENAI, endpoint(server)+"/v1", "synthetic-key", null))
                    .hasMessage("MODEL_METADATA_UNAVAILABLE");
        } finally { server.stop(0); }
    }

    private final LlmEndpointPolicy policy = new LlmEndpointPolicy(new LlmEndpointProperties(true));

    @Test void compatibleMetadataPreservesTenantPrefixAndBearerIsNeverInUrl() throws Exception {
        var paths = new ArrayList<String>(); var authorization = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString()); authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(2))) {
            var result = client.fetch(Provider.COMPATIBLE, endpoint(server)+"/tenant/v1", "synthetic-key", null);
            assertThat(result.models()).hasSize(1);
            assertThat(paths).containsExactly("/tenant/v1/models");
            assertThat(authorization).containsExactly("Bearer synthetic-key");
        } finally { server.stop(0); }
    }

    @Test void redirectsNeverReceiveCredentialsOrRequests() throws Exception {
        var followups = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            exchange.getResponseHeaders().set("Location", endpoint(server)+"/other");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/other", exchange -> { followups.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> client.fetch(Provider.OPENAI, endpoint(server)+"/v1", "synthetic-key", null))
                    .hasMessage("MODEL_METADATA_HTTP_ERROR");
            assertThat(followups).hasValue(0);
        } finally { server.stop(0); }
    }

    @Test void geminiUsesHeaderAuthenticationAndExplicitSiblingPathOnly() throws Exception {
        var paths = new ArrayList<String>(); var keys = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath()); keys.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] body = "{\"models\":[{\"name\":\"models/model\",\"inputTokenLimit\":32000}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(2))) {
            assertThat(client.fetch(Provider.GEMINI, endpoint(server)+"/tenant/v1beta/openai", "synthetic-key", null).models()).hasSize(1);
            assertThat(paths).containsExactly("/tenant/v1beta/models");
            assertThat(keys).containsExactly("synthetic-key");
        } finally { server.stop(0); }
    }

    @Test void unapprovedHttpAndMetadataTargetsAreRejectedBeforeDispatch() {
        AtomicInteger lookups = new AtomicInteger();
        Dns resolver = host -> {
            lookups.incrementAndGet();
            throw new java.net.UnknownHostException("Synthetic resolver must not be reached");
        };
        try (var client = new ModelMetadataClient(new LlmEndpointPolicy(new LlmEndpointProperties(false, List.of(), false)), resolver, Duration.ofSeconds(1))) {
            for (String target : List.of("http://169.254.169.254/v1", "http://192.168.1.50/v1", "https://user:secret@example.com/v1"))
                assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE, target, "synthetic-key", null))
                        .isInstanceOf(ModelMetadataClient.MetadataFailure.class).hasMessage("MODEL_METADATA_INVALID");
            assertThat(lookups).hasValue(0);
        }
    }
    @Test void canonicalizationCannotTurnAnAliasOrEscapedPathIntoAnotherCredentialScope() {
        try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(1))) {
            for (String target : List.of("https://2130706433/v1", "https://127.0.0.1/tenant/%2e%2e/v1"))
                assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE, target, "synthetic-key", null)).hasMessage("MODEL_METADATA_INVALID");
        }
    }

    @Test void completedCallsFreeCapacityBeforeTheWorkerWaitsForAnotherTask() throws Exception {
        var status = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            for (boolean succeeds : List.of(true, false)) {
                status.set(succeeds ? 200 : 429);
                var finishing = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var submitted = new CountDownLatch(1);
                var submissions = new AtomicInteger();
                var completions = new AtomicInteger();
                try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(10));
                        var callers = Executors.newSingleThreadExecutor()) {
                    installWorker(client, original -> new TestWorker(original) {
                        @Override public void execute(Runnable task) {
                            int count = submissions.incrementAndGet();
                            try { super.execute(task); }
                            finally { if (count == 2) submitted.countDown(); }
                        }
                        @Override protected void afterExecute(Runnable task, Throwable failed) {
                            if (completions.incrementAndGet() == 1) {
                                finishing.countDown();
                                awaitRelease(release);
                            }
                        }
                    });
                    try {
                        assertMetadataOutcome(client, endpoint(server), succeeds);
                        assertThat(finishing.await(5, TimeUnit.SECONDS)).isTrue();
                        var next = callers.submit(() -> assertMetadataOutcome(client, endpoint(server), succeeds));
                        assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
                        release.countDown();
                        next.get(5, TimeUnit.SECONDS);
                    } finally { release.countDown(); }
                }
            }
        } finally { server.stop(0); }
    }

    @Test void cancellationBeforeTheAdmittedTaskStartsDoesNotLeakCapacity() throws Exception {
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var canceledTaskFinished = new CountDownLatch(1);
        var completions = new AtomicInteger();
        try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofMillis(150))) {
            var worker = installWorker(client, original -> new TestWorker(original) {
                @Override protected void afterExecute(Runnable task, Throwable failed) {
                    if (completions.incrementAndGet() == 2) canceledTaskFinished.countDown();
                }
            });
            worker.execute(() -> { blocked.countDown(); awaitRelease(release); });
            try {
                assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE,
                        "https://user:secret@example.com/v1", "synthetic-key", null))
                        .hasMessage("MODEL_METADATA_TIMEOUT");
                assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE,
                        "https://user:secret@example.com/v1", "synthetic-key", null))
                        .hasMessage("MODEL_METADATA_BUSY");
            } finally { release.countDown(); }
            assertThat(canceledTaskFinished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE,
                    "https://user:secret@example.com/v1", "synthetic-key", null))
                    .hasMessage("MODEL_METADATA_INVALID");
        }
    }

    @Test void rejectedExecutionDoesNotLeakCapacity() throws Exception {
        var reject = new AtomicBoolean(true);
        try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(1))) {
            installWorker(client, original -> new TestWorker(original) {
                @Override public void execute(Runnable task) {
                    if (reject.getAndSet(false)) throw new RejectedExecutionException("Synthetic rejection");
                    super.execute(task);
                }
            });
            assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE,
                    "https://user:secret@example.com/v1", "synthetic-key", null))
                    .hasMessage("MODEL_METADATA_BUSY");
            assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE,
                    "https://user:secret@example.com/v1", "synthetic-key", null))
                    .hasMessage("MODEL_METADATA_INVALID");
        }
    }
    @Test void timeoutRetainsWorkerCapacityUntilAnUninterruptibleResolverReallyFinishes() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var completions = new AtomicInteger();
        Dns slow = host -> {
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { /* simulate platform resolver */ }
            return List.of(java.net.InetAddress.getLoopbackAddress());
        };
        try (var client = new ModelMetadataClient(policy, slow, Duration.ofMillis(150))) {
            installWorker(client, original -> new TestWorker(original) {
                @Override protected void afterExecute(Runnable task, Throwable failed) {
                    if (completions.incrementAndGet() == 2) finished.countDown();
                }
            });
            try {
                // An earlier completed call must not accidentally release the single slot twice.
                assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE,
                        "https://user:secret@example.com/v1", "synthetic-key", null))
                        .hasMessage("MODEL_METADATA_INVALID");
                assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE, "https://model.invalid/v1", "synthetic-key", null))
                        .hasMessage("MODEL_METADATA_TIMEOUT");
                assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE, "https://model.invalid/v1", "synthetic-key", null))
                        .hasMessage("MODEL_METADATA_BUSY");
            } finally { release.countDown(); }
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE,
                    "https://user:secret@example.com/v1", "synthetic-key", null))
                    .hasMessage("MODEL_METADATA_INVALID");
        }
    }
    @Test void excessiveDeclaredBodiesAndMixedMetadataDnsAreRejectedWithoutEchoingSecrets() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> { exchange.sendResponseHeaders(200, 8*1024*1024+1); exchange.close(); }); server.start();
        try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE, endpoint(server)+"/v1", "synthetic-key", null)).hasMessage("MODEL_METADATA_TOO_LARGE");
        } finally { server.stop(0); }
        Dns mixed = host -> List.of(java.net.InetAddress.getByName("10.0.0.5"), java.net.InetAddress.getByName("169.254.169.254"));
        try (var client = new ModelMetadataClient(policy, mixed, Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE, "https://model.invalid/v1", "synthetic-key", null))
                    .hasMessage("MODEL_METADATA_UNAVAILABLE");
        }
    }
    @Test void paginationUsesOnlyOpaqueCursorOnTheOriginalGeminiPath() throws Exception {
        var paths = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            boolean second = exchange.getRequestURI().getQuery() != null && exchange.getRequestURI().getQuery().contains("pageToken=");
            String value = second ? "{\"models\":[{\"name\":\"models/second\",\"inputTokenLimit\":16000}]}"
                    : "{\"models\":[{\"name\":\"models/first\"}],\"nextPageToken\":\"https://other.invalid/path?cursor=x\"}";
            byte[] body = value.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(3))) {
            var result = client.fetch(Provider.GEMINI, endpoint(server)+"/tenant/v1beta/openai", "synthetic-key", null);
            assertThat(result.models()).extracting(dev.agenvas.settings.application.ModelCatalog.Model::id).containsExactly("first", "second");
            assertThat(result.partial()).isFalse(); assertThat(paths).hasSize(2);
            assertThat(paths).allSatisfy(path -> assertThat(path).startsWith("/tenant/v1beta/models").doesNotContain("synthetic-key"));
        } finally { server.stop(0); }
    }
    @Test void anthropicMetadataUsesItsOwnHeaderWithoutBearerForwarding() throws Exception {
        var headers = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tenant/v1/models", exchange -> {
            headers.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("anthropic-version")).isEqualTo("2023-06-01");
            byte[] body = "{\"data\":[{\"id\":\"claude-synthetic\",\"max_input_tokens\":32000,\"max_tokens\":4096}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var client = new ModelMetadataClient(policy, Dns.SYSTEM, Duration.ofSeconds(3))) {
            assertThat(client.fetch(Provider.ANTHROPIC, endpoint(server)+"/tenant/v1", "synthetic-key", null).models().getFirst().inputLimitTokens()).isEqualTo(32000);
            assertThat(headers).containsExactly("synthetic-key");
        } finally { server.stop(0); }
    }
    private static String endpoint(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private static void assertMetadataOutcome(ModelMetadataClient client, String endpoint, boolean succeeds) {
        if (succeeds) {
            assertThat(client.fetch(Provider.COMPATIBLE, endpoint + "/v1", "synthetic-key", null).models())
                    .extracting(ModelCatalog.Model::id).containsExactly("model");
        } else {
            assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE, endpoint + "/v1", "synthetic-key", null))
                    .hasMessage("MODEL_METADATA_UNAVAILABLE");
        }
    }

    private static ThreadPoolExecutor installWorker(ModelMetadataClient client,
            java.util.function.Function<ThreadPoolExecutor, ThreadPoolExecutor> factory) throws Exception {
        // Preserve the real executor's bounds/queue while controlling only the handoff schedule.
        var field = ModelMetadataClient.class.getDeclaredField("worker");
        field.setAccessible(true);
        var original = (ThreadPoolExecutor) field.get(client);
        var replacement = factory.apply(original);
        field.set(client, replacement);
        original.shutdownNow();
        return replacement;
    }

    private static class TestWorker extends ThreadPoolExecutor {
        TestWorker(ThreadPoolExecutor original) {
            super(original.getCorePoolSize(), original.getMaximumPoolSize(),
                    original.getKeepAliveTime(TimeUnit.SECONDS), TimeUnit.SECONDS,
                    original.getQueue(), original.getThreadFactory());
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker barrier was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
