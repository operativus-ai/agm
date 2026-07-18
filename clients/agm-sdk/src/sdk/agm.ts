// AgmClient — the typed SDK facade. One instance per authenticated identity.

import { HttpClient } from './http.js';
import { collectRun, streamRun, type CollectOptions, type RunResult, type StreamHandlers } from './stream.js';
import type {
  AgentRun,
  AgentSession,
  AgentSummary,
  AuthResponse,
  BackgroundRunStatus,
  HitlDecision,
  KnowledgeBase,
  KnowledgeContent,
  Page,
  ProviderCredential,
  RagDocument,
  RunRequest,
  SyncRunResponse,
  ToolItem,
  WorkflowEdge,
  WorkflowExecution,
  WorkflowRunSummary,
  WorkflowStep,
  WorkflowSummary,
  WorkflowValidation,
} from '../types.js';

export class AgmClient {
  readonly http: HttpClient;

  constructor(baseUrl: string) {
    this.http = new HttpClient(baseUrl.replace(/\/$/, ''));
  }

  // ── Auth ──────────────────────────────────────────────────────────────────

  async login(username: string, password: string): Promise<AuthResponse> {
    const res = await this.http.post<AuthResponse>('/auth/login', { username, password });
    this.http.setToken(res.token);
    return res;
  }

  /** Server-side logout (writes the LOGOUT audit row), then drops the local token. */
  async logout(): Promise<void> {
    await this.http.post<void>('/auth/logout').catch(() => {});
    this.http.setToken(null);
  }

  /** For error-taxonomy tests: force an arbitrary (e.g. garbage) token. */
  setToken(token: string | null): void {
    this.http.setToken(token);
  }

  // ── Discovery ─────────────────────────────────────────────────────────────

  async listAgents(): Promise<AgentSummary[]> {
    // The backend serializes the identifier as `agentId` (no `id` key on the
    // wire). Normalize so callers can use `.id` in run/session paths.
    const raw = await this.http.get<AgentSummary[]>('/agents?includeInactive=false');
    return raw.map((a) => ({ ...a, id: a.agentId ?? a.id }));
  }

  // ── Runs ──────────────────────────────────────────────────────────────────

  /** Stream a run to completion and return the collected result (see RunResult). */
  run(agentId: string, request: RunRequest, options?: CollectOptions): Promise<RunResult> {
    return collectRun(this.http, agentId, request, options);
  }

  /** Stream a run with incremental frame delivery (live UI). Returns an abort fn. */
  stream(agentId: string, request: RunRequest, handlers: StreamHandlers): () => void {
    return streamRun(this.http, agentId, request, handlers);
  }

  /** Synchronous (non-streamed) run → POST /api/agents/{id}/runs. */
  runSync(agentId: string, request: RunRequest): Promise<SyncRunResponse> {
    return this.http.post<SyncRunResponse>(`/agents/${encodeURIComponent(agentId)}/runs`, request);
  }

  /** Background run → POST /api/agents/{id}/runs/background (poll with runStatus). */
  runBackground(agentId: string, request: RunRequest): Promise<BackgroundRunStatus> {
    return this.http.post<BackgroundRunStatus>(`/agents/${encodeURIComponent(agentId)}/runs/background`, request);
  }

  /** Batch status for background runs → GET /api/agents/{id}/runs/status?runIds=. */
  runStatusBatch(agentId: string, runIds: string[]): Promise<AgentRun[]> {
    return this.http.get<AgentRun[]>(`/agents/${encodeURIComponent(agentId)}/runs/status?runIds=${runIds.map(encodeURIComponent).join(',')}`);
  }

  // ── Tools ─────────────────────────────────────────────────────────────────

  listTools(): Promise<ToolItem[]> {
    return this.http.get<ToolItem[]>('/tools');
  }

  // ── Knowledge / RAG ───────────────────────────────────────────────────────

  createKnowledgeBase(name: string, description = ''): Promise<KnowledgeBase> {
    return this.http.post<KnowledgeBase>('/v1/knowledge-bases', { name, description });
  }
  deleteKnowledgeBase(id: string): Promise<unknown> {
    return this.http.delete(`/v1/knowledge-bases/${encodeURIComponent(id)}`);
  }
  assignAgentToKb(kbId: string, agentId: string): Promise<unknown> {
    return this.http.post(`/v1/knowledge-bases/${encodeURIComponent(kbId)}/agents/${encodeURIComponent(agentId)}`);
  }
  removeAgentFromKb(kbId: string, agentId: string): Promise<unknown> {
    return this.http.delete(`/v1/knowledge-bases/${encodeURIComponent(kbId)}/agents/${encodeURIComponent(agentId)}`);
  }
  /** Multipart upload of one or more text docs into a KB (async ingestion). */
  uploadDocs(kbId: string, files: File[]): Promise<unknown> {
    const form = new FormData();
    for (const f of files) form.append('files', f, f.name);
    return this.http.postForm(`/knowledge/upload-batch?knowledgeBaseId=${encodeURIComponent(kbId)}`, form);
  }
  listKnowledge(kbId: string): Promise<Page<KnowledgeContent>> {
    return this.http.get<Page<KnowledgeContent>>(`/knowledge?knowledgeBaseId=${encodeURIComponent(kbId)}&page=0&size=100`);
  }
  searchKnowledge(query: string): Promise<RagDocument[]> {
    return this.http.get<RagDocument[]>(`/knowledge/search?query=${encodeURIComponent(query)}`);
  }

  // ── Workflows ─────────────────────────────────────────────────────────────

  createWorkflow(name: string, description = ''): Promise<WorkflowSummary> {
    return this.http.post<WorkflowSummary>('/v1/workflows', { name, description });
  }
  deleteWorkflow(id: string): Promise<unknown> {
    return this.http.delete(`/v1/workflows/${encodeURIComponent(id)}`);
  }
  addWorkflowStep(id: string, step: WorkflowStep): Promise<WorkflowStep> {
    return this.http.post<WorkflowStep>(`/v1/workflows/${encodeURIComponent(id)}/steps`, step);
  }
  addWorkflowEdge(id: string, edge: WorkflowEdge): Promise<WorkflowEdge> {
    return this.http.post<WorkflowEdge>(`/v1/workflows/${encodeURIComponent(id)}/edges`, edge);
  }
  validateWorkflow(id: string): Promise<WorkflowValidation> {
    return this.http.get<WorkflowValidation>(`/v1/workflows/${encodeURIComponent(id)}/validate`);
  }
  runWorkflow(id: string, input: string, sessionId?: string): Promise<WorkflowExecution> {
    return this.http.post<WorkflowExecution>(`/v1/workflows/${encodeURIComponent(id)}/run`, { input, sessionId });
  }
  workflowRuns(id: string): Promise<Page<WorkflowRunSummary>> {
    return this.http.get<Page<WorkflowRunSummary>>(`/v1/workflows/${encodeURIComponent(id)}/runs?page=0&size=20`);
  }

  // ── Governance (provider credentials) ─────────────────────────────────────

  listProviderCredentials(): Promise<ProviderCredential[]> {
    return this.http.get<ProviderCredential[]>('/v1/provider-credentials');
  }
  upsertProviderCredential(provider: string, apiKey: string, label?: string): Promise<ProviderCredential> {
    return this.http.post<ProviderCredential>('/v1/provider-credentials', { provider, apiKey, label });
  }
  deleteProviderCredential(id: string): Promise<unknown> {
    return this.http.delete(`/v1/provider-credentials/${encodeURIComponent(id)}`);
  }

  // ── Sessions ──────────────────────────────────────────────────────────────

  listSessions(agentId: string): Promise<Page<AgentSession>> {
    return this.http.get<Page<AgentSession>>(
      `/sessions?page=0&size=20&agentId=${encodeURIComponent(agentId)}`,
    );
  }

  sessionRuns(sessionId: string): Promise<AgentRun[]> {
    return this.http.get<AgentRun[]>(`/sessions/${encodeURIComponent(sessionId)}/runs`);
  }

  // ── HITL ──────────────────────────────────────────────────────────────────

  resolveToolApproval(approvalId: string, decision: HitlDecision): Promise<unknown> {
    return this.http.post(`/v1/approvals/${encodeURIComponent(approvalId)}/resolve`, { decision });
  }

  resolveEscalation(escalationId: string, decision: HitlDecision): Promise<unknown> {
    return this.http.post(`/v1/escalations/${encodeURIComponent(escalationId)}/resolve`, { decision });
  }

  // ── Health / liveness ─────────────────────────────────────────────────────

  /**
   * Is the backend reachable at all? Probes /v3/api-docs (public under springdoc)
   * and falls back to /actuator/health — ANY http response (even 401/503) means
   * the process is up; only a network error means down.
   */
  async isReachable(): Promise<boolean> {
    for (const path of ['/v3/api-docs', '/actuator/health']) {
      try {
        await fetch(`${this.http.baseUrl}${path}`);
        return true;
      } catch {
        // try next probe
      }
    }
    return false;
  }

  /** The prod shallow-liveness endpoint (may 401 in dev — that still proves liveness). */
  async healthStatus(): Promise<number> {
    const res = await fetch(`${this.http.baseUrl}/api/v1/health`, {
      headers: this.http.authHeaders(),
    });
    return res.status;
  }
}
