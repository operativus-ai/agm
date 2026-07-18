package ai.operativus.agentmanager.compute.mcp;

import ai.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import ai.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import ai.operativus.agentmanager.core.model.McpTransport;
import ai.operativus.agentmanager.core.security.SsrfGuard;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpClientTransport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Responsibility: Manages the lifecycle of dynamic MCP (Model Context Protocol) client connections.
 * Provides programmatic connection pooling for MCP servers registered via the UI's Extensions page,
 * enabling hot-reload of external tools without application restart.
 *
 * @architecture Cluster-safe via Redis PubSub events dispatched by McpClusterEventListener.
 *               On startup, performs a full reconciliation scan against the database.
 *               Implements AutoCloseable for graceful shutdown of all MCP connections.
 * State: Stateful (maintains active connection pool)
 */
@Service
public class McpConnectionPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionPool.class);

    private final ExtensionRegistrationRepository extensionRepository;

    /**
     * Pool of active MCP connections, keyed by extension ID.
     * Each entry contains the list of ToolCallbacks discovered from that MCP server.
     */
    private final Map<String, List<ToolCallback>> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, io.modelcontextprotocol.client.McpSyncClient> mcpClients = new ConcurrentHashMap<>();

    /**
     * Owning org per connected extension id, captured from the loaded entity at connect time.
     * The pool is a process-wide singleton holding every tenant's connections; this map is what
     * lets {@link #getToolCallbacksForOrg(String)} hand an agent only the MCP tools from its own
     * org's extensions (#1132). Populated in {@link #connectEntity}, cleared in {@link #disconnect}.
     */
    private final Map<String, String> extensionOrgId = new ConcurrentHashMap<>();

    public McpConnectionPool(ExtensionRegistrationRepository extensionRepository) {
        this.extensionRepository = extensionRepository;
    }

    /**
     * @summary Performs a full reconciliation scan at startup, connecting to all active MCP extensions.
     * @logic Queries the extensions table for all active MCP entries and attempts to establish
     *        connections. This ensures eventual consistency even if Redis PubSub messages were
     *        lost during a network partition.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("McpConnectionPool: Performing full reconciliation scan for active MCP extensions...");
        List<ExtensionRegistrationEntity> mcpExtensions = extensionRepository.findAll().stream()
                .filter(ext -> "MCP".equalsIgnoreCase(ext.getType()) && Boolean.TRUE.equals(ext.getActive()))
                .toList();

        if (mcpExtensions.isEmpty()) {
            log.info("McpConnectionPool: No active MCP extensions found in the registry.");
            return;
        }

        for (ExtensionRegistrationEntity ext : mcpExtensions) {
            connectEntity(ext);
        }
        log.info("McpConnectionPool: Reconciliation complete. {} MCP connections active.", activeConnections.size());
    }

    /**
     * @summary Establishes an MCP client connection for an extension id and discovers its tools.
     * @logic Loads the extension row (single source of truth for url / transport / auth) and
     *        delegates to {@link #connectEntity}. Keeping the public seam id-only means callers
     *        — the Redis cluster listener and the admin reconnect endpoint — never carry the
     *        decrypted auth secret across a process or wire boundary.
     * @param extensionId Unique ID of the extension registration.
     */
    public void connect(String extensionId) {
        ExtensionRegistrationEntity ext = extensionRepository.findById(extensionId).orElse(null);
        if (ext == null) {
            log.warn("McpConnectionPool: connect requested for unknown extension '{}'; ignoring.", extensionId);
            return;
        }
        connectEntity(ext);
    }

    /**
     * @summary Opens the transport for a loaded extension and registers its discovered tools.
     * @logic Branches SSE vs streamable-HTTP on {@code ext.transport}, applies an
     *        {@code Authorization: Bearer} header when an auth secret is present (decrypted
     *        transparently by the JPA converter on entity read), and never logs the secret.
     */
    private void connectEntity(ExtensionRegistrationEntity ext) {
        String extensionId = ext.getId();
        String url = ext.getUrl();
        // Record ownership before any early-return so disconnect can always clean up and
        // getToolCallbacksForOrg never serves an extension whose org we failed to record.
        // org_id is NOT NULL in the schema; a null here (legacy/test row) is left unrecorded
        // so the connection's tools stay invisible to every org — fail-closed, never leaked.
        if (ext.getOrgId() != null) {
            extensionOrgId.put(extensionId, ext.getOrgId());
        }
        if (activeConnections.containsKey(extensionId)) {
            log.debug("McpConnectionPool: Extension {} already connected; skipping.", extensionId);
            return;
        }

        // Runtime SSRF backstop. Write-time validation in ExtensionController rejects new
        // registrations targeting loopback / RFC-1918 / 169.254 cloud-metadata / non-http(s)
        // schemes, but a row persisted before that guard existed — or any path that bypasses
        // the controller (DB-direct insert, future bulk-import endpoint) — would still reach
        // here at startup reconcile or via the Redis cluster event listener. This check
        // ensures the transport is never opened against those targets.
        String ssrfReason = SsrfGuard.validate(url, false);
        if (ssrfReason != null) {
            log.warn("McpConnectionPool: refusing MCP connection for extension '{}' — SSRF guard: {} (url={})",
                    extensionId, ssrfReason, url);
            // Register empty list so getToolCallbacks returns [] and the rest of the system
            // continues to function; do not propagate (startup reconcile + PubSub listeners
            // have no caller to surface the rejection to).
            activeConnections.put(extensionId, new ArrayList<>());
            return;
        }

        McpTransport transportType;
        try {
            transportType = McpTransport.from(ext.getTransport());
        } catch (IllegalArgumentException e) {
            log.warn("McpConnectionPool: extension '{}' has unknown transport '{}'; refusing connection.",
                    extensionId, ext.getTransport());
            activeConnections.put(extensionId, new ArrayList<>());
            return;
        }

        log.info("McpConnectionPool: Connecting to MCP server '{}' at URL: {} (transport={}, auth={}).",
                extensionId, url, transportType, ext.getAuthSecret() != null ? "yes" : "no");
        try {
            McpClientTransport transport = buildTransport(transportType, url, ext.getAuthSecret());
            var client = io.modelcontextprotocol.client.McpClient.sync(transport).build();
            client.initialize();

            ToolCallback[] toolArray = org.springframework.ai.mcp.SyncMcpToolCallbackProvider.builder()
                    .mcpClients(List.of(client))
                    .build()
                    .getToolCallbacks();

            activeConnections.put(extensionId, new ArrayList<>(java.util.Arrays.asList(toolArray)));
            mcpClients.put(extensionId, client);
            log.info("McpConnectionPool: Successfully connected to MCP extension '{}'. Tools discovered: {}.", extensionId, toolArray.length);
        } catch (Exception e) {
            // Never echo the auth secret — log only the transport + message. A 401 from a bad
            // token lands here and registers [] tools; the /reconnect endpoint surfaces it as
            // connected=false to the operator.
            log.error("McpConnectionPool: Failed to connect to MCP server '{}' at {} (transport={}): {}",
                    extensionId, url, transportType, e.getMessage());
            // Register empty list so reconnect can be attempted later
            activeConnections.put(extensionId, new ArrayList<>());
        }
    }

    /**
     * @summary Builds the mcp-core client transport for the given transport type, optionally
     *          attaching a bearer-auth request customizer.
     * @logic SSE and streamable-HTTP have distinct builder types but share the
     *        {@link McpSyncHttpClientRequestCustomizer} hook; the switch is exhaustive so a
     *        new {@link McpTransport} variant is a compile error rather than a runtime default.
     */
    // Package-private (not private) so McpTransportAuthHeaderTest can drive the built transport
    // against a WireMock server and assert the bearer header reaches the wire — the pool's
    // runtime SSRF guard hard-rejects the loopback URL WireMock binds to, so an end-to-end test
    // through connect() cannot exercise this.
    static McpClientTransport buildTransport(McpTransport transportType, String url, String auth) {
        boolean hasAuth = auth != null && !auth.isBlank();
        McpSyncHttpClientRequestCustomizer authCustomizer = hasAuth
                ? (requestBuilder, method, uri, body, context) ->
                        requestBuilder.header("Authorization", "Bearer " + auth)
                : null;

        return switch (transportType) {
            case STREAMABLE_HTTP -> {
                var b = HttpClientStreamableHttpTransport.builder(url);
                if (authCustomizer != null) {
                    b = b.httpRequestCustomizer(authCustomizer);
                }
                yield b.build();
            }
            case SSE -> {
                var b = HttpClientSseClientTransport.builder(url);
                if (authCustomizer != null) {
                    b = b.httpRequestCustomizer(authCustomizer);
                }
                yield b.build();
            }
        };
    }

    /**
     * @summary Gracefully disconnects an MCP client and removes its tools from the pool.
     * @param extensionId Unique ID of the extension to disconnect.
     */
    public void disconnect(String extensionId) {
        List<ToolCallback> removed = activeConnections.remove(extensionId);
        extensionOrgId.remove(extensionId);
        var client = mcpClients.remove(extensionId);
        if (client != null) {
            try { client.closeGracefully(); } catch (Exception e) { log.debug("Error closing MCP client: {}", e.getMessage()); }
        }
        if (removed != null) {
            log.info("McpConnectionPool: Disconnected MCP extension '{}'. {} tools removed.", extensionId, removed.size());
        } else {
            log.debug("McpConnectionPool: Extension '{}' was not connected; nothing to disconnect.", extensionId);
        }
    }

    /**
     * @summary Retrieves all ToolCallbacks discovered from a specific MCP extension.
     * @param extensionId Unique ID of the extension.
     * @return List of ToolCallbacks, or empty list if not connected.
     */
    public List<ToolCallback> getToolCallbacks(String extensionId) {
        return activeConnections.getOrDefault(extensionId, Collections.emptyList());
    }

    /**
     * @summary Retrieves all ToolCallbacks from all active MCP connections.
     * @return Flattened list of all MCP-provided tools across all connections.
     */
    public List<ToolCallback> getAllToolCallbacks() {
        List<ToolCallback> all = new ArrayList<>();
        activeConnections.values().forEach(all::addAll);
        return all;
    }

    /**
     * @summary Tool callbacks from only the extensions owned by {@code orgId} — the tenant-safe
     *     replacement for {@link #getAllToolCallbacks()} on the agent-execution path (#1132).
     * @logic Fail-closed: a null/blank org (e.g. a run with no bound tenant context) yields an
     *     empty list rather than leaking another tenant's tools. Matches the security posture of
     *     {@code AgentSecurityFilters.buildVectorFilter} (deny on null orgId).
     */
    public List<ToolCallback> getToolCallbacksForOrg(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Collections.emptyList();
        }
        List<ToolCallback> scoped = new ArrayList<>();
        activeConnections.forEach((extensionId, callbacks) -> {
            if (orgId.equals(extensionOrgId.get(extensionId))) {
                scoped.addAll(callbacks);
            }
        });
        return scoped;
    }

    /**
     * @summary Returns the set of currently connected MCP extension IDs.
     */
    public java.util.Set<String> getConnectedExtensionIds() {
        return Collections.unmodifiableSet(activeConnections.keySet());
    }

    /**
     * @summary Gracefully closes all active MCP connections on application shutdown.
     */
    @PreDestroy
    @Override
    public void close() {
        log.info("McpConnectionPool: Shutting down. Closing {} active MCP connections.", activeConnections.size());
        mcpClients.values().forEach(client -> {
            try { client.closeGracefully(); } catch (Exception e) { log.debug("Error closing MCP client: {}", e.getMessage()); }
        });
        mcpClients.clear();
        activeConnections.clear();
        extensionOrgId.clear();
    }
}
