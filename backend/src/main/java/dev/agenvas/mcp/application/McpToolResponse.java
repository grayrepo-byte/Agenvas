package dev.agenvas.mcp.application;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Safe business projection plus bounded image previews; no transport or private storage data. */
public record McpToolResponse(JsonNode data, List<Image> images) {
    public McpToolResponse { images = List.copyOf(images); }
    public McpToolResponse(JsonNode data) { this(data, List.of()); }
    /** Base64 bytes of a server-controlled preview, never a reusable download URL. */
    public record Image(String mimeType, String data) {}
}
