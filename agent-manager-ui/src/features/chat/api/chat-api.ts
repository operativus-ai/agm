import type { RunRequest, StreamChunk, RunResponse } from '../types';
import type { Approval } from '../../../shared/types/orchestration';
import { ApiClient } from '../../../shared/api/client';
import { STORAGE_KEYS } from '../../../shared/constants/storage-keys';

const API_BASE_URL = '/agents';

export type HitlDecision = 'APPROVED' | 'REJECTED';

export interface EscalationResolveResponse {
  escalationId: string;
  runId: string;
  decision: HitlDecision;
}

export class ChatApi {

  static async sendMessage(agentId: string, request: RunRequest): Promise<RunResponse> {
    return ApiClient.post<RunResponse>(`${API_BASE_URL}/${agentId}/runs`, request);
  }

  static async startBackgroundRun(agentId: string, request: RunRequest): Promise<{ runId: string, status: string }> {
    return ApiClient.post<{ runId: string, status: string }>(`${API_BASE_URL}/${agentId}/runs/background`, request);
  }

  // Resolves a TOOL_APPROVAL HITL pause keyed by the typed approvalId carried in
  // `RequiredAction.approvalId`. Replaces the deleted runId-keyed endpoint
  // POST /api/agents/{id}/runs/{runId}/continue (removed in PR #352 / F1 dedup).
  static async resolveToolApproval(approvalId: string, decision: HitlDecision): Promise<Approval> {
    return ApiClient.post<Approval>(`/v1/approvals/${approvalId}/resolve`, { decision });
  }

  // Resolves a SWARM_ESCALATION_APPROVAL HITL pause keyed by the escalationId carried
  // in `RequiredAction.escalationId`. Sister surface to `resolveToolApproval`; the
  // backend dispatches the resume on a virtual thread, so this returns 202 + the runId
  // echo immediately.
  static async resolveEscalation(escalationId: string, decision: HitlDecision): Promise<EscalationResolveResponse> {
    return ApiClient.post<EscalationResolveResponse>(`/v1/escalations/${escalationId}/resolve`, { decision });
  }

  static streamMessage(
    agentId: string,
    request: RunRequest, 
    onChunk: (chunk: StreamChunk) => void,
    onError: (error: Error) => void,
    onComplete: () => void
  ): () => void {
    const controller = new AbortController();
    
    // Using fetch directly because streaming needs special handling
    // We manually add the auth header here.
    const token = localStorage.getItem(STORAGE_KEYS.AUTH_TOKEN);
    const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
    };
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }

    // SSE — typed body N/A; see contract audit 2026-05-09. Raw fetch is used
    // (not ApiClient.stream) because this stream needs custom chunk handling for
    // the chat run-event protocol. EventSource carries discrete `event`/`data`
    // strings, not a typed JSON body, so a `<T>` generic does not apply.
    fetch(`/api${API_BASE_URL}/${agentId}/runs/stream`, {
      method: 'POST',
      headers,
      body: JSON.stringify(request),
      signal: controller.signal,
    }).then(async (response) => {
      if (response.status === 401) {
          localStorage.removeItem(STORAGE_KEYS.AUTH_TOKEN);
          localStorage.removeItem(STORAGE_KEYS.AUTH_USER);
          window.location.href = '/login';
          throw new Error('Unauthorized');
      }

      if (!response.ok) {
        // Surface the server's problem+json detail (e.g. "No API key configured for
        // provider 'GOOGLE'…") instead of the opaque statusText, so the chat error tells
        // the user what actually went wrong and how to fix it.
        let detail = response.statusText || `HTTP ${response.status}`;
        try {
          const body = await response.json();
          detail = body?.detail || body?.message || body?.error || detail;
        } catch {
          // non-JSON / empty body — keep the status text
        }
        throw new Error(detail);
      }
      
      const reader = response.body?.getReader();
      const decoder = new TextDecoder();
      
      if (!reader) throw new Error('No readable stream available');

      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        
        // Keep the last incomplete line for the next iteration
        buffer = lines.pop() || '';
        
        for (const line of lines) {
            if (line.startsWith('data:')) {
                const data = line.slice(5).trim();
                // Skip heartbeat or empty
                if (!data) continue;
                
                if (data === '[DONE]') {
                    onComplete();
                    return;
                }
                try {
                    const parsed = JSON.parse(data);
                    onChunk(parsed);
                } catch (e) {
                    console.warn('Failed to parse SSE data', data, e);
                }
            }
        }
      }
      onComplete();
    }).catch(onError);

    return () => controller.abort();
  }

  static async cancelRun(agentId: string, runId: string): Promise<void> {
    return ApiClient.delete<void>(`${API_BASE_URL}/${agentId}/runs/${runId}`);
  }
}
