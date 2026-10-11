package dev.agenvas.provider.domain;

import java.io.IOException;
import java.io.InputStream;

/** Caller owns the response stream and closes it after archive or rejection. */
public record MediaPayload(InputStream stream, String declaredContentType, tools.jackson.databind.JsonNode providerMetadata) implements AutoCloseable {
    public MediaPayload(InputStream stream, String declaredContentType) { this(stream, declaredContentType, null); }
    @Override
    public void close() throws IOException {
        stream.close();
    }
}
