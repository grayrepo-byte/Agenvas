package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.shared.error.ApiProblemException;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fixes which addresses a self-hosted deployment may aim its LLM endpoint at. Private ranges and
 * proxy fake-ip answers are allowed on purpose; cloud metadata endpoints are not, in any deployment.
 */
class LlmEndpointPolicyTest {

    /** Deployment without the loopback exception, the default posture. */
    private final LlmEndpointPolicy policy = new LlmEndpointPolicy(new LlmEndpointProperties(false));

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
                "224.0.0.1", "255.255.255.255", "::1", "fe80::1")) {
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
