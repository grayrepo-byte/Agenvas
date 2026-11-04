package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.shared.error.ApiProblemException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.junit.jupiter.api.Test;

/**
 * Fixes which addresses a self-hosted deployment may aim its LLM endpoint at. Private ranges and
 * proxy fake-ip answers are allowed on purpose; cloud metadata endpoints are not, in any deployment.
 */
class LlmEndpointPolicyTest {

    /** Deployment without the loopback exception, the default posture. */
    private final LlmEndpointPolicy policy = new LlmEndpointPolicy(new LlmEndpointProperties(false));

    @Test
    void permitsOnlyAnExplicitDeploymentTrustedHttpOrigin() {
        LlmEndpointPolicy optedIn = configured("http://192.168.1.50:11434");
        assertThat(optedIn.normalize("http://192.168.1.50:11434/v1/"))
                .isEqualTo("http://192.168.1.50:11434/v1");
        assertThatThrownBy(() -> policy.normalize("http://192.168.1.50:11434/v1"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> optedIn.normalize("http://192.168.1.50:8080/v1"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> optedIn.normalize("http://192.168.1.51:11434/v1"))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void bindsAnEmptyAllowlistAndMultipleExactOrigins() {
        assertThatThrownBy(() -> configured("").normalize("http://192.168.1.50:11434/v1"))
                .isInstanceOf(ApiProblemException.class);
        LlmEndpointPolicy optedIn = configured("http://192.168.1.50:11434,http://[fd12::1]:11434/");
        assertThat(optedIn.normalize("http://[fd12::1]:11434/v1"))
                .isEqualTo("http://[fd12::1]:11434/v1");
    }

    @Test
    void trustedOriginsDoNotOverrideMetadataOrLoopbackProtection() {
        for (String host : List.of("169.254.169.254", "127.0.0.1", "[::1]", "[fe80::1]", "0.0.0.0",
                "[fd00:ec2::254]", "[fd20:ce::254]")) {
            String endpoint = "http://" + host + ":11434";
            assertThatThrownBy(() -> configured(endpoint).normalize(endpoint + "/v1"))
                    .as("trusted origin cannot authorize %s", host)
                    .isInstanceOf(ApiProblemException.class);
        }
    }

    @Test
    void malformedOperatorAllowlistFailsClosedWithoutEchoingCredentials() {
        for (String origin : List.of("https://192.168.1.50:11434", "http://*.internal:11434",
                "http://192.168.1.50:11434/v1", "http://user:secret@192.168.1.50:11434",
                "http://192.168.1.50:0", "http://192.168.1.50:65536",
                "http://192.168.1.50:", "http://192.168.1.50?token=secret", "http://192.168.1.50#fragment")) {
            assertThatThrownBy(() -> new LlmEndpointPolicy(new LlmEndpointProperties(false, List.of(origin))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Trusted LLM HTTP entries must be exact HTTP origins");
        }
    }

    private LlmEndpointPolicy configured(String origins) {
        var source = new MapConfigurationPropertySource(Map.of(
                "agenvas.settings.llm.trusted-http-origins", origins));
        var settings = new Binder(source).bind("agenvas.settings.llm",
                Bindable.of(LlmEndpointProperties.class)).orElse(new LlmEndpointProperties(false));
        return new LlmEndpointPolicy(settings);
    }

    @Test
    void allowsPrivateAndProxyFakeIpAnswers() throws Exception {
        for (String address : List.of("10.0.0.5", "172.16.3.9", "192.168.1.50",
                "198.18.0.188", "198.19.255.1", "fc00::1", "fd12:3456::1", "2001:2::59")) {
            assertThatCode(() -> policy.requireAllowedAddress("llm.internal",
                    InetAddress.getByName(address)))
                    .as("self-hosted address %s", address)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void stillRejectsCloudMetadataAndNonRoutableAnswers() throws Exception {
        for (String address : List.of("169.254.169.254", "169.254.1.1", "0.0.0.0",
                "224.0.0.1", "255.255.255.255", "::1", "fe80::1",
                "fd00:ec2::254", "fd20:ce::254")) {
            assertThatThrownBy(() -> policy.requireAllowedAddress("llm.internal",
                    InetAddress.getByName(address)))
                    .as("blocked address %s", address)
                    .isInstanceOf(ApiProblemException.class);
        }
    }

    @Test
    void stillRejectsLoopbackUnlessTheDeploymentOptsIn() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertThatThrownBy(() -> policy.requireAllowedAddress("127.0.0.1", loopback))
                .isInstanceOf(ApiProblemException.class);

        LlmEndpointPolicy optedIn =
                new LlmEndpointPolicy(new LlmEndpointProperties(true));
        assertThatCode(() -> optedIn.requireAllowedAddress("127.0.0.1", loopback))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsOrdinaryPublicAnswers() throws Exception {
        assertThatCode(() -> policy.requireAllowedAddress("api.deepseek.com",
                InetAddress.getByName("8.8.8.8"))).doesNotThrowAnyException();
    }
}
