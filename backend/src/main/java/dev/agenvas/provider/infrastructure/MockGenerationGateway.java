package dev.agenvas.provider.infrastructure;

import dev.agenvas.provider.domain.GenerationGateway;
import dev.agenvas.provider.domain.GenerationRequest;
import dev.agenvas.provider.domain.GenerationResult;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "mock", matchIfMissing = true)
public class MockGenerationGateway implements GenerationGateway {

    @Override
    public GenerationResult submit(GenerationRequest request) {
        String providerRequestId = deterministicRequestId(request);
        return switch (request.fixture()) {
            case SUCCESS -> new GenerationResult(
                    GenerationResult.Status.COMPLETED, providerRequestId, true, null);
            case FAILURE -> new GenerationResult(
                    GenerationResult.Status.FAILED,
                    providerRequestId,
                    true,
                    "MOCK_PROVIDER_REJECTED");
            case UNKNOWN -> new GenerationResult(
                    GenerationResult.Status.UNKNOWN,
                    providerRequestId,
                    true,
                    "MOCK_PROVIDER_SUBMISSION_UNKNOWN");
        };
    }

    private String deterministicRequestId(GenerationRequest request) {
        String source = request.projectId() + ":" + request.requestKey();
        UUID id = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        return "mock-" + id;
    }
}
