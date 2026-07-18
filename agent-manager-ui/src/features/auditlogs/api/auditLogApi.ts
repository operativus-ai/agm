import type { PaginatedResponse } from '../../../shared/types/api';
import { ApiClient } from '../../../shared/api/client';
import { STORAGE_KEYS } from '../../../shared/constants/storage-keys';

export interface AuditLogEntry {
  id: string;
  agentId: string;
  action: string;
  username?: string;
  changeset?: string;
  versionNumber?: number;
  createdAt: string;
}

export interface SystemAuditLogEntry {
  id: string;
  orgId?: string;
  username?: string;
  action: string;
  resourceType: string;
  resourceId?: string;
  httpMethod?: string;
  requestPath?: string;
  responseStatus?: number;
  createdAt: string;
}

const BASE = '/admin/audit-logs';
const SYSTEM_BASE = '/admin/system-audit-logs';

export class AuditLogApi {
  static async listAuditLogs(
    params: { username?: string; action?: string; agentId?: string; page?: number; size?: number }
  ): Promise<PaginatedResponse<AuditLogEntry>> {
    const query = new URLSearchParams();
    if (params.username) query.set('username', params.username);
    if (params.action) query.set('action', params.action);
    if (params.agentId) query.set('agentId', params.agentId);
    query.set('page', String(params.page ?? 0));
    query.set('size', String(params.size ?? 50));
    return ApiClient.get<PaginatedResponse<AuditLogEntry>>(`${BASE}?${query.toString()}`);
  }

  /**
   * Export agent audit logs as CSV (T013). Bypasses ApiClient because that assumes
   * JSON parsing — here we want the raw `text/csv` body. Reuses the same Bearer-token
   * auth header pattern as ApiClient.request. The backend caps responses at 10K rows
   * server-side; operators wanting more should slice by filter.
   * Maps to Backend: GET /api/admin/audit-logs/export
   */
  static async exportAuditLogsCsv(
    params: { username?: string; action?: string; agentId?: string }
  ): Promise<string> {
    const query = new URLSearchParams();
    if (params.username) query.set('username', params.username);
    if (params.action) query.set('action', params.action);
    if (params.agentId) query.set('agentId', params.agentId);
    const qs = query.toString();
    const url = `/api${BASE}/export${qs ? `?${qs}` : ''}`;

    const token = localStorage.getItem(STORAGE_KEYS.AUTH_TOKEN);
    const headers: Record<string, string> = { Accept: 'text/csv' };
    if (token) headers['Authorization'] = `Bearer ${token}`;

    const response = await fetch(url, { method: 'GET', headers });
    if (!response.ok) {
      throw new Error(`Export failed: ${response.status} ${response.statusText}`);
    }
    return response.text();
  }

  static async listSystemAuditLogs(
    params: { username?: string; action?: string; resourceType?: string; resourceId?: string; page?: number; size?: number }
  ): Promise<PaginatedResponse<SystemAuditLogEntry>> {
    const query = new URLSearchParams();
    if (params.username) query.set('username', params.username);
    if (params.action) query.set('action', params.action);
    if (params.resourceType) query.set('resourceType', params.resourceType);
    if (params.resourceId) query.set('resourceId', params.resourceId);
    query.set('page', String(params.page ?? 0));
    query.set('size', String(params.size ?? 50));
    return ApiClient.get<PaginatedResponse<SystemAuditLogEntry>>(`${SYSTEM_BASE}?${query.toString()}`);
  }
}
