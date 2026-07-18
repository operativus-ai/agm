package com.operativus.agentmanager.core.model;

/**
 * Transport an MCP {@code Extension} uses to reach its server. Persisted as a VARCHAR on
 * {@code extensions.transport}; the {@code McpConnectionPool} switches on this to build the
 * matching Spring AI / mcp-core client transport.
 *
 * <p>{@link #SSE} is the back-compatible default (every row pre-dating this column reads as SSE).
 * {@link #STREAMABLE_HTTP} is required by most modern remote MCPs (e.g. the hosted GitHub MCP).</p>
 */
public enum McpTransport {
    SSE,
    STREAMABLE_HTTP;

    /** Parses a stored/inbound value, defaulting blank/null to {@link #SSE}; throws on unknown. */
    public static McpTransport from(String value) {
        if (value == null || value.isBlank()) {
            return SSE;
        }
        return McpTransport.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
