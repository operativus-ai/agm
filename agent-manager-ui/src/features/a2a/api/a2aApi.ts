import { ApiClient } from '../../../shared/api/client';

// ── Types ────────────────────────────────────────────────────────

export interface AgentCard {
  agentId: string;
  name: string;
  description: string;
  capabilities: string[];
  securityTier: number;
  dataZone: string | null;
  endpointUrl: string | null;
  modelId: string;
  maxTokenBudget: number | null;
  publishedAt: string;
}

export interface RemoteAgentRegistration {
  id: string;
  remoteAgentId: string;
  baseUrl: string;
  alias: string;
  lastResolvedCard: AgentCard | null;
  registeredAt: string;
  lastVerifiedAt: string | null;
}

export interface RegisterPeerRequest {
  remoteAgentId: string;
  baseUrl: string;
  alias: string;
  apiKey: string;
}

/** §22.5 inbound peer-cancellation notify body. AGM accepts this from a peer that has
 *  cancelled a task we originated; the row lands on `a2a_task_events` with
 *  `status=CANCELLED, message="notify-received"` for operator forensics. */
export interface PeerCancellationNotify {
  taskId: string;
  reason?: string | null;
  initiatingAgentId?: string | null;
}

/** Lifecycle states of an inbound A2A task. Mirrors backend `A2aTaskStatus`. */
export type A2aTaskStatus =
  | 'SUBMITTED'
  | 'WORKING'
  | 'PAUSED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'BUDGET_HALT';

/** Body of a `submitTask` POST. `taskId` is server-generated when omitted. */
export interface A2aTaskRequest {
  taskId?: string | null;
  targetAgentId: string;
  input: string;
  initiatingAgentId?: string | null;
  sessionId?: string | null;
  traceId?: string | null;
  finOpsBoundary?: unknown;
}

/** Payload of each `a2a-task-status` SSE event from `POST /api/v1/a2a/tasks`. */
export interface A2aTaskStatusEvent {
  taskId: string;
  status: A2aTaskStatus;
  runId: string | null;
  message: string | null;
  errorDetail: string | null;
  timestamp: string;
}

const TERMINAL_STATUSES: ReadonlySet<A2aTaskStatus> = new Set([
  'COMPLETED',
  'FAILED',
  'CANCELLED',
]);

export function isTerminalA2aStatus(status: A2aTaskStatus): boolean {
  return TERMINAL_STATUSES.has(status);
}

// ── API Client ───────────────────────────────────────────────────

const BASE = '/v1/a2a';

export class A2aApi {
  static async listCards(): Promise<AgentCard[]> {
    return ApiClient.get<AgentCard[]>(`${BASE}/cards`);
  }

  static async getCard(agentId: string): Promise<AgentCard> {
    return ApiClient.get<AgentCard>(`${BASE}/cards/${agentId}`);
  }

  static async listPeers(): Promise<RemoteAgentRegistration[]> {
    return ApiClient.get<RemoteAgentRegistration[]>(`${BASE}/peers`);
  }

  static async registerPeer(request: RegisterPeerRequest): Promise<RemoteAgentRegistration> {
    return ApiClient.post<RemoteAgentRegistration>(`${BASE}/peers`, request);
  }

  static async deregisterPeer(alias: string): Promise<void> {
    return ApiClient.delete<void>(`${BASE}/peers/${alias}`);
  }

  /**
   * Cancels an active inbound A2A task by its server-issued taskId. Returns 204
   * on success; the underlying ApiClient surfaces 404 (unknown / already terminal)
   * as a thrown ApiError the caller can branch on.
   * Maps to backend: DELETE /api/v1/a2a/tasks/{taskId}.
   */
  static async cancelTask(taskId: string): Promise<void> {
    return ApiClient.delete<void>(`${BASE}/tasks/${encodeURIComponent(taskId)}`);
  }

  /**
   * §22.5 — sends a peer-cancellation notify to AGM's own inbound hook. Used by an
   * automated test or operator tool that needs to inject a synthetic notify into
   * the audit trail without spinning up a real peer. Returns 204 on success; 400
   * if the body is missing the correlation taskId.
   * Maps to backend: POST /api/v1/a2a/peers/cancel-notify.
   */
  static async sendCancellationNotify(notify: PeerCancellationNotify): Promise<void> {
    return ApiClient.post<void>(`${BASE}/peers/cancel-notify`, notify);
  }

  /**
   * Path helper for streaming task submission. The backend responds with SSE
   * (Content-Type: text/event-stream), so consumers need `fetchEventSource` or
   * `EventSource` rather than ApiClient.post — this helper just resolves the
   * absolute URL so callers can attach auth headers and stream consumers in one
   * place. Maps to backend: POST /api/v1/a2a/tasks (SSE).
   */
  static submitTaskUrl(): string {
    return `/api${BASE}/tasks`;
  }

  /**
   * Submits an A2A task and streams `a2a-task-status` lifecycle events back to
   * the caller via SSE. Returns the AbortController that owns the underlying
   * fetch — call `.abort()` to close the stream early. Terminal statuses
   * (COMPLETED / FAILED / CANCELLED) close the stream from the server side.
   *
   * Maps to backend: POST /api/v1/a2a/tasks (Content-Type: text/event-stream).
   */
  static streamTask(
    request: A2aTaskRequest,
    options: {
      onStatus: (event: A2aTaskStatusEvent) => void;
      onOpen?: () => void;
      onError?: (err: unknown) => void;
      onClose?: () => void;
    }
  ): AbortController {
    // SSE — typed body N/A; see contract audit 2026-05-09. EventSource carries
    // discrete `event`/`data` strings, not a typed JSON body, so ApiClient.stream
    // intentionally does not take a `<T>` generic.
    return ApiClient.stream(`${BASE}/tasks`, {
      method: 'POST',
      body: request,
      onOpen: options.onOpen,
      onError: options.onError,
      onClose: options.onClose,
      onMessage: (msg) => {
        if (msg.event && msg.event !== 'a2a-task-status') return;
        try {
          options.onStatus(JSON.parse(msg.data) as A2aTaskStatusEvent);
        } catch (err) {
          options.onError?.(err);
        }
      },
    });
  }
}
