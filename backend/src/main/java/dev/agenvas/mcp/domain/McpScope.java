package dev.agenvas.mcp.domain;

/** Explicit user grants; editing content never implies permission to incur media usage. */
public enum McpScope {
    READ,
    CONTENT_WRITE,
    MEDIA_GENERATE
}
