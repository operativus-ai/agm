// Typed HTTP core for the AGM SDK. Node-native fetch; token held in memory
// (no localStorage in a headless client).

/**
 * Error taxonomy — every non-2xx maps to one AgmApiError whose `kind` a test
 * can branch on without string-matching ad hoc:
 *  - 'auth'          401 — stale/invalid JWT (or a principal whose org is null)
 *  - 'provider-key'  400 whose detail says no LLM API key is configured — an
 *                    ENVIRONMENT gap (admin must POST /api/v1/provider-credentials),
 *                    not a client bug. Tests should WARN/SKIP, not FAIL.
 *  - 'validation'    other 4xx business validation (concurrency cap, media to
 *                    non-vision model, orchestration depth ≥ 5, …)
 *  - 'server'        5xx
 */
export type AgmErrorKind = 'auth' | 'provider-key' | 'validation' | 'server';

export class AgmApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public kind: AgmErrorKind,
    public body?: unknown,
  ) {
    super(message);
    this.name = 'AgmApiError';
  }
}

export function classifyError(status: number, detail: string): AgmErrorKind {
  if (status === 401) return 'auth';
  if (status >= 500) return 'server';
  if (/no api key configured/i.test(detail)) return 'provider-key';
  return 'validation';
}

/** Extract the human detail from an RFC 7807 problem+json (or fallback shapes). */
export function problemDetail(body: unknown, fallback: string): string {
  const b = body as { detail?: string; message?: string; error?: string } | undefined;
  return b?.detail ?? b?.message ?? b?.error ?? fallback;
}

export class HttpClient {
  private token: string | null = null;

  constructor(public readonly baseUrl: string) {}

  setToken(token: string | null): void {
    this.token = token;
  }

  authHeaders(): Record<string, string> {
    return this.token ? { Authorization: `Bearer ${this.token}` } : {};
  }

  async request<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
    const response = await fetch(`${this.baseUrl}/api${endpoint}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...this.authHeaders(),
        ...(options.headers as Record<string, string> | undefined),
      },
    });

    if (!response.ok) {
      const body = await response.json().catch(() => undefined);
      const detail = problemDetail(body, response.statusText || `HTTP ${response.status}`);
      throw new AgmApiError(detail, response.status, classifyError(response.status, detail), body);
    }

    if (response.status === 204) return undefined as T;
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  get<T>(endpoint: string): Promise<T> {
    return this.request<T>(endpoint);
  }

  post<T>(endpoint: string, body?: unknown): Promise<T> {
    return this.request<T>(endpoint, {
      method: 'POST',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }

  put<T>(endpoint: string, body?: unknown): Promise<T> {
    return this.request<T>(endpoint, {
      method: 'PUT',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }

  delete<T>(endpoint: string): Promise<T> {
    return this.request<T>(endpoint, { method: 'DELETE' });
  }

  /**
   * Multipart POST (file uploads). Deliberately does NOT set Content-Type —
   * fetch injects the correct multipart/form-data boundary for the FormData body.
   */
  async postForm<T>(endpoint: string, form: FormData): Promise<T> {
    const response = await fetch(`${this.baseUrl}/api${endpoint}`, {
      method: 'POST',
      headers: this.authHeaders(),
      body: form,
    });
    if (response.status === 401) {
      this.setToken(null);
      throw new AgmApiError('Unauthorized — please sign in again', 401, 'auth');
    }
    if (!response.ok) {
      const body = await response.json().catch(() => undefined);
      const detail = problemDetail(body, response.statusText || `HTTP ${response.status}`);
      throw new AgmApiError(detail, response.status, classifyError(response.status, detail), body);
    }
    if (response.status === 204) return undefined as T;
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }
}
