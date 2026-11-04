package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.settings.application.LlmEndpointProperties;
import dev.agenvas.shared.error.ApiProblemException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;

/** An operator HTTP exception never bypasses runtime origin, IP or pinned DNS checks. */
class SafeLlmTransportPolicyTest {
    @Test
    void removingAnHttpOriginRevokesPersistedConfigurationsBeforeAnyRequest() {
        var defaultPolicy = new LlmEndpointPolicy(new LlmEndpointProperties(false));
        assertThatThrownBy(() -> new SafeLlmTransport("http://192.168.1.50:11434/v1", defaultPolicy))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void rejectsMetadataIpLiteralsEvenWhenAnOperatorAccidentallyTrustsTheOrigin() {
        var policy = new LlmEndpointPolicy(new LlmEndpointProperties(false,
                List.of("http://169.254.169.254:11434")));
        assertThatThrownBy(() -> new SafeLlmTransport("http://169.254.169.254:11434/v1", policy))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void rejectsTrustedHostDnsRebindingToMetadataBeforeNetworkConnection() {
        String endpoint = "http://model.internal:11434/v1";
        var policy = new LlmEndpointPolicy(new LlmEndpointProperties(false,
                List.of("http://model.internal:11434")));
        var transport = new SafeLlmTransport(endpoint, policy,
                host -> List.of(InetAddress.getByName("192.168.1.50"),
                        InetAddress.getByName("169.254.169.254")));
        var client = new OkHttpClient.Builder().addInterceptor(transport.interceptor()).build();
        var request = new Request.Builder().url(endpoint + "/chat/completions")
                .header("Authorization", "Bearer synthetic-local-key")
                .post(RequestBody.create("{}", okhttp3.MediaType.get("application/json"))).build();
        assertThatThrownBy(() -> client.newCall(request).execute())
                .isInstanceOf(UnknownHostException.class).hasMessageContaining("blocked address");
    }
}
