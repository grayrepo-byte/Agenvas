package dev.agenvas.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Public example secrets must never be accepted as administrator setup authority. */
class IdentityPropertiesTest {

    @Test
    void rejectsHistoricalExampleSecretsWithoutEchoingTheirValues() {
        for (String example : new String[] {"local-bootstrap-secret-change-me",
                "replace-with-a-long-random-bootstrap-secret"}) {
            assertThatThrownBy(() -> new IdentityProperties(example))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(example);
        }
        assertThat(new IdentityProperties("unique-deployment-bootstrap-2026-secret")
                .bootstrapSecret()).isEqualTo("unique-deployment-bootstrap-2026-secret");
    }
}
