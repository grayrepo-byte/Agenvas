package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.provider.domain.GenerationRequest;
import dev.agenvas.provider.domain.GenerationResult;
import dev.agenvas.provider.domain.MockFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MockGenerationGatewayTest {

    private final MockGenerationGateway gateway = new MockGenerationGateway();

    @Test
    void returnsTheSameProviderRequestIdForAnIdempotentReplay() {
        UUID projectId = UUID.fromString("d014fa53-c3c6-4706-84f9-c7ab1988fb5e");
        GenerationRequest request = new GenerationRequest(projectId, "plan-a:image-1:1", MockFixture.SUCCESS);

        GenerationResult first = gateway.submit(request);
        GenerationResult replay = gateway.submit(request);

        assertThat(first.status()).isEqualTo(GenerationResult.Status.ACCEPTED);
        assertThat(first.demoOutput()).isTrue();
        assertThat(replay.providerRequestId()).isEqualTo(first.providerRequestId());
    }

    @Test
    void exposesRepeatableFailureAndUnknownFixtures() {
        UUID projectId = UUID.fromString("d014fa53-c3c6-4706-84f9-c7ab1988fb5e");

        GenerationResult failure = gateway.submit(
                new GenerationRequest(projectId, "failure", MockFixture.FAILURE));
        GenerationResult unknown = gateway.submit(
                new GenerationRequest(projectId, "unknown", MockFixture.UNKNOWN));

        assertThat(failure.status()).isEqualTo(GenerationResult.Status.FAILED);
        assertThat(failure.errorCode()).isEqualTo("MOCK_PROVIDER_REJECTED");
        assertThat(unknown.status()).isEqualTo(GenerationResult.Status.UNKNOWN);
        assertThat(unknown.errorCode()).isEqualTo("MOCK_PROVIDER_SUBMISSION_UNKNOWN");
    }
}
