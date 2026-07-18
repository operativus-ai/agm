package com.operativus.agentmanager.core.model;

/**
 * Wire shape for {@code /api/v1/extensions} create/list/update responses.
 *
 * <p>{@code version} is non-null on responses (the entity's optimistic-lock counter as of
 * the read) and is REQUIRED on {@code PUT} requests — the controller compares it against
 * the loaded entity and rejects mismatches with 409. {@code POST} requests may omit it
 * (the field defaults to 0 on insert).</p>
 *
 * <p>MCP auth is asymmetric, mirroring Provider Credentials: {@code auth} is <b>write-only</b>
 * (the raw bearer secret on POST/PUT) and is ALWAYS {@code null} on responses — {@code toDto}
 * never populates it. {@code authPreview} is <b>read-only</b> (a masked {@code ****last4} hint,
 * or {@code null} when no secret is set) and is ignored on requests. {@code transport} is
 * {@code SSE} (default) or {@code STREAMABLE_HTTP}; see {@link McpTransport}.</p>
 */
public record ExtensionRegistrationDTO(
    String id,
    String name,
    String type, // 'MCP' | 'WEBHOOK' | 'NATIVE_SPI'
    String url,
    String description,
    boolean active,
    Long version,
    String transport,   // 'SSE' | 'STREAMABLE_HTTP'; read+write
    String auth,         // write-only raw secret; ALWAYS null on responses
    String authPreview   // read-only masked hint; ignored on requests
) {
    /** Legacy 6-arg constructor for callers that pre-date the version field (defaults version to null). */
    public ExtensionRegistrationDTO(String id, String name, String type, String url,
                                    String description, boolean active) {
        this(id, name, type, url, description, active, null, null, null, null);
    }

    /** 7-arg constructor (id..version) for callers that pre-date the transport/auth fields. */
    public ExtensionRegistrationDTO(String id, String name, String type, String url,
                                    String description, boolean active, Long version) {
        this(id, name, type, url, description, active, version, null, null, null);
    }
}
