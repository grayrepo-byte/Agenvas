package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmEndpointPolicyTest {
    private final LlmEndpointPolicy policy = new LlmEndpointPolicy();

    @Test void acceptsHttpAndHttpsWithoutDnsPreflightOrAddressAllowlist() {
        for (String host : List.of("localhost", "127.0.0.1", "[::1]", "10.0.0.5", "198.18.1.3",
                "169.254.169.254", "[fd00:ec2::254]", "model-not-yet-running.invalid")) {
            for (String scheme : List.of("http", "https")) {
                String url = scheme + "://" + host + ":11434/v1";
                assertThat(policy.normalize(url + "/")).isEqualTo(url);
            }
        }
    }

    @Test void normalizesCaseAndTrailingSlashesWithoutChangingApiPrefix() {
        assertThat(policy.normalize(" HTTPS://MODEL.INVALID:8443/tenant/v1/ "))
                .isEqualTo("https://model.invalid:8443/tenant/v1");
    }

    @Test void rejectsMalformedUrlsAndCredentialsInTheAddress() {
        for (String url : List.of("file:///etc/hosts", "http://user:secret@localhost/v1", "http://localhost:0/v1",
                "http://localhost:65536/v1", "http://localhost/v1?key=secret", "http://localhost/v1#fragment",
                "http://localhost/tenant/../v1")) {
            assertThatThrownBy(() -> policy.normalize(url)).as(url).isInstanceOf(ApiProblemException.class);
        }
    }
}
