package dev.agenvas.provider.domain;

public interface GenerationGateway {

    GenerationResult submit(GenerationRequest request);
}
