package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.settings.application.LlmEndpointProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Outbound redirect, origin, path and DNS checks run before a credential can leave the host. */
class SafeLlmTransportTest {

    private HttpServer server;
    private final AtomicInteger completions = new AtomicInteger();
    private final AtomicInteger redirects = new AtomicInteger();
    private final LlmEndpointPolicy localPolicy = new LlmEndpointPolicy(
            new LlmEndpointProperties(true));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            completions.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/leak");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/leak", exchange -> {
            redirects.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void rejectsRedirectWithoutSendingAuthorizationToItsTarget() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        SafeLlmTransport transport = new SafeLlmTransport(base, localPolicy);
        OkHttpClient outer = new OkHttpClient.Builder()
                .addInterceptor(transport.interceptor()).build();
        Request request = post(base + "/chat/completions");
        assertThatThrownBy(() -> { try (var ignored = outer.newCall(request).execute()) { } })
                .isInstanceOf(IOException.class).hasMessageContaining("redirect");
        assertThat(completions).hasValue(1);
        assertThat(redirects).hasValue(0);
    }

    @Test
    void rejectsDifferentPathAndPortBeforeSendingARequest() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        SafeLlmTransport transport = new SafeLlmTransport(base, localPolicy);
        OkHttpClient outer = new OkHttpClient.Builder()
                .addInterceptor(transport.interceptor()).build();
        assertThatThrownBy(() -> outer.newCall(post(base + "/models")).execute())
                .isInstanceOf(IOException.class).hasMessageContaining("target differs");
        assertThatThrownBy(() -> outer.newCall(post("http://127.0.0.1:1/v1/chat/completions"))
                .execute()).isInstanceOf(IOException.class).hasMessageContaining("target differs");
        assertThat(completions).hasValue(0);
    }

    @Test
    void rejectsPrivateDnsAnswerAtConnectionTime() throws Exception {
        String base = "https://public.example/v1";
        SafeLlmTransport transport = new SafeLlmTransport(base,
                new LlmEndpointPolicy(new LlmEndpointProperties(false)),
                host -> List.of(InetAddress.getByName("169.254.169.254")));
        OkHttpClient outer = new OkHttpClient.Builder()
                .addInterceptor(transport.interceptor()).build();
        assertThatThrownBy(() -> outer.newCall(post(base + "/chat/completions")).execute())
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("blocked address");
    }

    @Test
    void loopbackExceptionIsExactAndRequiresDeploymentOptIn() throws Exception {
        LlmEndpointPolicy defaultPolicy = new LlmEndpointPolicy(new LlmEndpointProperties(false));
        assertThatThrownBy(() -> defaultPolicy.normalize("https://127.0.0.1/v1"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> localPolicy.normalize("http://localhost/v1"))
                .isInstanceOf(RuntimeException.class);
        assertThat(localPolicy.normalize("http://127.0.0.1:8080/v1"))
                .isEqualTo("http://127.0.0.1:8080/v1");
        assertThatThrownBy(() -> localPolicy.requireAllowedAddress("public.example",
                InetAddress.getByName("127.0.0.1")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> localPolicy.requireAllowedAddress("public.example",
                InetAddress.getByName("100.64.0.1")))
                .isInstanceOf(RuntimeException.class);
    }

    private Request post(String url) {
        return new Request.Builder().url(url)
                .header("Authorization", "Bearer never-leak-this")
                .post(RequestBody.create("{}", okhttp3.MediaType.get("application/json")))
                .build();
    }
}
