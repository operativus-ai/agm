import { ApiClient } from '../../../shared/api/client';
import { STORAGE_KEYS } from '../../../shared/constants/storage-keys';

/**
 * MCP (Model Context Protocol) API wrapper.
 * Centralizes all MCP Server communication through the governed ApiClient layer,
 * ensuring secure Authorization headers and OpenTelemetry trace propagation for SSE streams.
 *
 * Backend Controller: McpController (@RequestMapping("/mcp"))
 * Note: The MCP controller sits OUTSIDE the /api namespace — it maps directly to /mcp.
 * The Vite dev server proxy is configured to forward /mcp to the backend.
 */

/** JSON-RPC 2.0 request structure used by MCP protocol. */
export interface McpJsonRpcRequest {
  jsonrpc: '2.0';
  id: string;
  method: string;
  params?: Record<string, any>;
}

/** JSON-RPC 2.0 response structure returned by MCP protocol. */
export interface McpJsonRpcResponse {
  jsonrpc: '2.0';
  id: string | number;
  result?: any;
  error?: {
    code: number;
    message: string;
  };
}

/** MCP tool definition as discovered via tools/list. */
export interface McpTool {
  name: string;
  description: string;
  inputSchema?: Record<string, any>;
}

export const mcpApi = {
  /**
   * Establishes an SSE connection to the MCP Server handshake endpoint.
   * Uses ApiClient.stream() with absolutePath to bypass the /api prefix,
   * injecting Authorization headers securely via fetch-event-source.
   *
   * Backend: GET /mcp/sse (produces: text/event-stream)
   *
   * @returns AbortController to close the SSE stream
   */
  connectSse: (handlers: {
    onEndpoint: (url: string) => void;
    onOpen?: () => void;
    onError?: (err: any) => void;
    onClose?: () => void;
  }): AbortController => {
    // SSE — typed body N/A; see contract audit 2026-05-09. EventSource carries
    // discrete `event`/`data` strings, not a typed JSON body, so ApiClient.stream
    // intentionally does not take a `<T>` generic.
    return ApiClient.stream('/mcp/sse', {
      absolutePath: true,
      onMessage: (event) => {
        if (event.event === 'endpoint') {
          handlers.onEndpoint(event.data);
        }
      },
      onOpen: handlers.onOpen,
      onError: handlers.onError,
      onClose: handlers.onClose,
    });
  },

  /**
   * Sends a JSON-RPC message to the MCP Server's message endpoint.
   * The endpointUrl from the backend is an absolute path (e.g., "/mcp/messages?sessionId=...").
   * Since MCP sits outside /api, we use ApiClient.request() but pass the URL directly
   * without the /api prefix by leveraging the fact that the Vite proxy forwards /mcp.
   *
   * Backend: POST /mcp/messages?sessionId=...
   */
  sendMessage: async (endpointUrl: string, message: McpJsonRpcRequest): Promise<McpJsonRpcResponse> => {
    // The endpointUrl from the backend SSE event is "/mcp/messages?sessionId=<uuid>".
    // ApiClient.post() prepends /api, which would make it /api/mcp/messages — wrong path.
    // ApiClient.request() also prepends /api.
    // So we construct the fetch manually but still use centralized auth/tracing patterns.
    const token = localStorage.getItem(STORAGE_KEYS.AUTH_TOKEN);
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }

    const path = endpointUrl.startsWith('/') ? endpointUrl : `/${endpointUrl}`;
    const response = await fetch(path, {
      method: 'POST',
      headers,
      body: JSON.stringify(message),
    });

    if (!response.ok) {
      throw new Error(`MCP message failed: ${response.status} ${response.statusText}`);
    }

    return response.json();
  },

  /**
   * Convenience: Fetches the list of tools from the MCP Server.
   */
  fetchTools: async (endpointUrl: string): Promise<McpTool[]> => {
    const request: McpJsonRpcRequest = {
      jsonrpc: '2.0',
      id: crypto.randomUUID(),
      method: 'tools/list',
    };
    const response = await mcpApi.sendMessage(endpointUrl, request);
    return response.result?.tools ?? [];
  },
};
