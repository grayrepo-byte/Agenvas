package dev.agenvas.mcp.domain;

/** Only these exact native-client endpoints accept an MCP credential instead of a browser session. */
public final class McpHttpEndpoints {
    public static final String TRANSPORT = "/mcp";
    public static final String UPLOAD = "/mcp/uploads";
    public static final long MAX_UPLOAD_REQUEST_BYTES = 501L * 1024 * 1024;
    public static final int MAX_UPLOAD_METADATA_BYTES = 16 * 1024;

    private McpHttpEndpoints() {
    }
}
