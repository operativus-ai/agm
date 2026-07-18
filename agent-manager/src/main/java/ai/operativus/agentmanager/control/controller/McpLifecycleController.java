package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.compute.mcp.McpConnectionPool;
import ai.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import ai.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.model.TenantConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Domain Responsibility: Admin lifecycle surface for AgentManager's <i>outbound</i> MCP client
 *   connection pool. Distinct in concern from {@link McpController} which exposes AgentManager
 *   <i>as</i> an MCP server (inbound JSON-RPC). This controller surfaces the state of the
 *   {@link McpConnectionPool} (status / per-server breakdown) and a deliberate sync reconnect
 *   verb for operators. Path namespace is {@code /api/mcp/...} to keep it cleanly separated
 *   from the inbound {@code /mcp/...} surface used by external MCP clients.
 *
 * State: Stateless. Pool state lives in {@link McpConnectionPool}.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpLifecycleController {

    private static final Logger log = LoggerFactory.getLogger(McpLifecycleController.class);

    private static final long RECONNECT_TIMEOUT_SECONDS = 10L;

    private final McpConnectionPool connectionPool;
    private final ExtensionRegistrationRepository extensionRepository;

    public McpLifecycleController(McpConnectionPool connectionPool,
                                   ExtensionRegistrationRepository extensionRepository) {
        this.connectionPool = connectionPool;
        this.extensionRepository = extensionRepository;
    }

    /** Caller's tenant, falling back to the system org when no context is bound (#1132). */
    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
    }

    /**
     * @summary Pool-wide aggregate view: how many MCP extensions are configured (DB rows of
     *     {@code type=MCP}), how many are currently connected, and how many tools the pool
     *     exposes in total. Public read-only.
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        String orgId = callerOrgId();
        List<ExtensionRegistrationEntity> mcpExtensions = extensionRepository.findByOrgId(orgId).stream()
                .filter(e -> "MCP".equalsIgnoreCase(e.getType()))
                .toList();
        Set<String> connectedIds = connectionPool.getConnectedExtensionIds();

        int configured = mcpExtensions.size();
        int active = (int) mcpExtensions.stream().filter(e -> Boolean.TRUE.equals(e.getActive())).count();
        // connected/tool counts scoped to this org's extensions (#1132)
        int connected = (int) mcpExtensions.stream().filter(e -> connectedIds.contains(e.getId())).count();
        int totalTools = connectionPool.getToolCallbacksForOrg(orgId).size();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", configured);
        body.put("active", active);
        body.put("connected", connected);
        body.put("totalTools", totalTools);
        return body;
    }

    /**
     * @summary Per-server breakdown for the admin UI. Joins the configured extension rows with
     *     live connection-pool state — every configured MCP extension appears once with a
     *     {@code connectionStatus} of CONNECTED / DISCONNECTED.
     */
    @GetMapping("/servers")
    public List<Map<String, Object>> listServers() {
        Set<String> connectedIds = connectionPool.getConnectedExtensionIds();
        return extensionRepository.findByOrgId(callerOrgId()).stream()
                .filter(e -> "MCP".equalsIgnoreCase(e.getType()))
                .map(ext -> {
                    boolean connected = connectedIds.contains(ext.getId());
                    int toolCount = connected ? connectionPool.getToolCallbacks(ext.getId()).size() : 0;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", ext.getId());
                    row.put("name", ext.getName());
                    row.put("url", ext.getUrl());
                    row.put("active", Boolean.TRUE.equals(ext.getActive()));
                    row.put("connectionStatus", connected ? "CONNECTED" : "DISCONNECTED");
                    row.put("toolCount", toolCount);
                    return row;
                })
                .toList();
    }

    /**
     * @summary Operator action: drop and re-establish the MCP connection for a single extension.
     *     Synchronous with a 10-second deadline (decision §7.5 of the SDD) — beyond that we
     *     surface a 504 Gateway Timeout so the UI can render a deterministic failure banner
     *     instead of a perpetual spinner. ADMIN-only (decision §7.3).
     * @logic Looks up the extension row, disconnects the pool entry (no-op if absent), then
     *     reconnects to the persisted URL on a worker thread bounded by {@link #RECONNECT_TIMEOUT_SECONDS}.
     *     Connection success/failure is reflected in the response body's {@code connected} flag —
     *     the underlying {@link McpConnectionPool#connect} swallows transport errors, so we
     *     re-check the pool's {@link McpConnectionPool#getConnectedExtensionIds() connected set}
     *     after the call to determine actual outcome.
     */
    @PostMapping("/servers/{id}/reconnect")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> reconnectServer(@PathVariable("id") String id) {
        ExtensionRegistrationEntity entity = extensionRepository.findByIdAndOrgId(id, callerOrgId()).orElse(null);
        if (entity == null || !"MCP".equalsIgnoreCase(entity.getType())) {
            return ResponseEntity.notFound().build();
        }
        if (entity.getUrl() == null || entity.getUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "MISSING_URL",
                    "message", "MCP extension '" + id + "' has no URL configured"));
        }

        long startNanos = System.nanoTime();
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            connectionPool.disconnect(id);
            connectionPool.connect(id);
        });

        boolean timedOut = false;
        try {
            task.get(RECONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            timedOut = true;
            task.cancel(true);
            log.warn("MCP reconnect for '{}' exceeded {}s deadline", id, RECONNECT_TIMEOUT_SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).body(Map.of("error", "INTERRUPTED"));
        } catch (ExecutionException e) {
            log.warn("MCP reconnect for '{}' failed: {}", id, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        }

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        boolean connected = connectionPool.getConnectedExtensionIds().contains(id);
        int toolCount = connected ? connectionPool.getToolCallbacks(id).size() : 0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("connected", connected);
        body.put("toolCount", toolCount);
        body.put("durationMs", durationMs);
        body.put("status", connected ? "RECONNECTED" : (timedOut ? "TIMEOUT" : "FAILED"));

        if (timedOut) {
            return ResponseEntity.status(504).body(body);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * @summary Local sanity utility — exposed mostly to discourage code from string-comparing
     *     URL segments elsewhere. Kept package-private intentionally.
     */
    static String normalizeType(String type) {
        return type == null ? null : type.toUpperCase(Locale.ROOT);
    }
}
