package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.settings.application.LlmEndpointPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;

class SafeLlmTransportPolicyTest {
    @Test void configuredHostnameCanResolveToLoopbackAndCompleteTheRequest() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1); exchange.close();
        }); server.start();
        try {
            String endpoint = "http://model.invalid:" + server.getAddress().getPort() + "/v1";
            var transport = new SafeLlmTransport(endpoint, new LlmEndpointPolicy(),
                    ignored -> List.of(InetAddress.getByName("127.0.0.1")));
            var client = new OkHttpClient.Builder().addInterceptor(transport.interceptor()).build();
            var request = new Request.Builder().url(endpoint + "/chat/completions")
                    .post(RequestBody.create("{}", okhttp3.MediaType.get("application/json"))).build();
            try (var response = client.newCall(request).execute()) { assertThat(response.code()).isEqualTo(200); }
        } finally { server.stop(0); }
    }
}
