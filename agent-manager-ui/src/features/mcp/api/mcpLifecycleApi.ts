import { ApiClient } from '../../../shared/api/client';

/**
 * MCP outbound-client lifecycle admin API.
 *
 * Distinct in concern from {@link ./mcpApi} which talks to AgentManager
 * **as an MCP server** (inbound JSON-RPC at `/mcp/*`). This module wraps the
 * `/api/mcp/*` surface exposed by `McpLifecycleController` and reports the
 * state of AgentManager's **outbound** MCP client connection pool —
 * configured servers, live connection status, per-server tool counts, and an
 * operator-fired sync reconnect verb.
 *
 * Backend controller: `control/controller/McpLifecycleController.java`
 * (`@RequestMapping("/api/mcp")`).
 *
 * UI hookup: not yet wired into `McpAdminPage`. Surfaced here for type
 * visibility of the existing BE contract — the audit-2026-05-10 sweep
 * flagged these endpoints as orphans because no FE consumer referenced
 * them. Adding the client lets a future "Outbound MCP Servers" panel
 * import typed calls instead of inlining raw `fetch` or untyped
 * `ApiClient.get<any>(...)`.
 */

/** Pool-wide aggregate counts returned by `GET /api/mcp/status`. */
export interface McpPoolStatus {
  /** Number of MCP-type rows in the extension registry. */
  configured: number;
  /** Subset of `configured` whose `active` flag is true. */
  active: number;
  /** Subset of `configured` that has a live pool connection right now. */
  connected: number;
  /** Sum of tool callbacks exposed across every connected server. */
  totalTools: number;
}

/** Per-server row returned by `GET /api/mcp/servers`. */
export interface McpServerSummary {
  id: string;
  name: string;
  /** Persisted URL the pool will reconnect to. */
  url: string;
  /** Mirrors `ExtensionRegistration.active`. */
  active: boolean;
  /** Live connection state, NOT the persisted `active` flag. */
  connectionStatus: 'CONNECTED' | 'DISCONNECTED';
  /** 0 when `connectionStatus = DISCONNECTED`. */
  toolCount: number;
}

/**
 * Result of `POST /api/mcp/servers/{id}/reconnect`. BE may return:
 * - 200 with `status: 'RECONNECTED' | 'FAILED'`
 * - 504 with `status: 'TIMEOUT'` (10s deadline exceeded)
 * - 404 if `id` is unknown or not an MCP-type extension
 * - 400 if the extension has no URL configured
 * The `connected` boolean is the canonical post-action signal — `status`
 * is a human-readable refinement for the UI banner.
 */
export interface McpReconnectResponse {
  id: string;
  connected: boolean;
  toolCount: number;
  /** Wall-clock duration of the disconnect+reconnect cycle, server-side. */
  durationMs: number;
  status: 'RECONNECTED' | 'FAILED' | 'TIMEOUT';
}

const BASE = '/mcp';

export const mcpLifecycleApi = {
  /** GET /api/mcp/status — pool-wide aggregate counts. Public read-only. */
  getStatus: () => ApiClient.get<McpPoolStatus>(`${BASE}/status`),

  /** GET /api/mcp/servers — per-server breakdown for an admin table. */
  listServers: () => ApiClient.get<McpServerSummary[]>(`${BASE}/servers`),

  /**
   * POST /api/mcp/servers/{id}/reconnect — synchronous reconnect with a
   * 10-second BE-side deadline. ADMIN-only.
   */
  reconnect: (id: string) =>
    ApiClient.post<McpReconnectResponse>(`${BASE}/servers/${encodeURIComponent(id)}/reconnect`),
};
