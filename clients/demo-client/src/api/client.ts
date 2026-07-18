// Minimal typed fetch wrapper for the AGM REST surface.
// BASE_URL '/api' is rewritten by the Vite dev proxy to the AGM backend (:8080).

const BASE_URL = '/api';
const TOKEN_KEY = 'agm_demo_token';

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public body?: unknown,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

export function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function request<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${BASE_URL}${endpoint}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
      ...(options.headers as Record<string, string> | undefined),
    },
  });

  if (response.status === 401) {
    // Stale/invalid JWT (or a token whose user lost its org) — force re-login.
    clearToken();
    throw new ApiError('Unauthorized — please sign in again', 401);
  }

  if (!response.ok) {
    // AGM returns RFC 7807 problem+json — surface `detail` when present
    // (e.g. 400 "No API key configured for provider 'OPENAI'").
    const body = await response.json().catch(() => undefined);
    const detail =
      (body as { detail?: string; message?: string; error?: string } | undefined)?.detail ??
      (body as { message?: string } | undefined)?.message ??
      (body as { error?: string } | undefined)?.error ??
      response.statusText;
    throw new ApiError(detail || `HTTP ${response.status}`, response.status, body);
  }

  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  get: <T>(endpoint: string) => request<T>(endpoint),
  post: <T>(endpoint: string, body?: unknown) =>
    request<T>(endpoint, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  delete: <T>(endpoint: string) => request<T>(endpoint, { method: 'DELETE' }),
};
