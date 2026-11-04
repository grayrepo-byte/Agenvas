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
        try (var client = new ModelMetadataClient(new LlmEndpointPolicy(new LlmEndpointProperties(false)), resolver, Duration.ofSeconds(1))) {
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
    @Test void timeoutRetainsWorkerCapacityUntilAnUninterruptibleResolverReallyFinishes() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        Dns slow = host -> {
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { /* simulate platform resolver */ }
            return List.of(java.net.InetAddress.getLoopbackAddress());
        };
        try (var client = new ModelMetadataClient(policy, slow, Duration.ofMillis(150))) {
            assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE, "https://model.invalid/v1", "synthetic-key", null))
                    .hasMessage("MODEL_METADATA_TIMEOUT");
            assertThatThrownBy(() -> client.fetch(Provider.COMPATIBLE, "https://model.invalid/v1", "synthetic-key", null))
                    .hasMessage("MODEL_METADATA_BUSY");
        } finally { release.countDown(); }
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
}
