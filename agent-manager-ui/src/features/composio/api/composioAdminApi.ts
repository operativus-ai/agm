import { ApiClient } from '../../../shared/api/client';
import type {
  ComposioActionConfigResponse,
  ComposioActionConfigCreateRequest,
  ComposioActionConfigUpdateRequest,
  ComposioConnectionConfigResponse,
  ComposioConnectionConfigUpsertRequest,
  ConfigDriftResponse,
  ComposioCatalogListResponse,
  ComposioCatalogImportRequest,
  ComposioCatalogImportResponse,
} from '../types';

const ACTIONS_BASE = '/admin/composio/actions';
const CONNECTION_BASE = '/admin/composio/connection';
const DRIFT_BASE = '/admin/composio/config-drift';
const CATALOG_BASE = '/admin/composio/catalog';

export const composioAdminApi = {
  // Action config endpoints (SUPER_ADMIN). BE maps list + create at the class root
  // (@GetMapping / @PostMapping on /api/admin/composio/actions) — no /list, no trailing slash.
  listActions: () => ApiClient.get<ComposioActionConfigResponse[]>(ACTIONS_BASE),
  createAction: (req: ComposioActionConfigCreateRequest) =>
    ApiClient.post<ComposioActionConfigResponse>(ACTIONS_BASE, req),
  updateAction: (id: string, req: ComposioActionConfigUpdateRequest) =>
    ApiClient.put<ComposioActionConfigResponse>(`${ACTIONS_BASE}/${id}`, req),
  deleteAction: (id: string) => ApiClient.delete<void>(`${ACTIONS_BASE}/${id}`),

  // Connection config endpoints (ADMIN, per-org)
  getConnection: () => ApiClient.get<ComposioConnectionConfigResponse>(CONNECTION_BASE),
  upsertConnection: (req: ComposioConnectionConfigUpsertRequest) =>
    ApiClient.put<ComposioConnectionConfigResponse>(CONNECTION_BASE, req),
  deleteConnection: () => ApiClient.delete<void>(CONNECTION_BASE),

  // Config drift endpoint (SUPER_ADMIN)
  getConfigDrift: () => ApiClient.get<ConfigDriftResponse>(DRIFT_BASE),

  // Upstream catalog browse + bulk import (SUPER_ADMIN)
  listCatalog: (app?: string, limit?: number) => {
    const qs = new URLSearchParams();
    if (app?.trim()) qs.set('app', app.trim());
    if (limit != null) qs.set('limit', String(limit));
    const q = qs.toString();
    return ApiClient.get<ComposioCatalogListResponse>(CATALOG_BASE + (q ? `?${q}` : ''));
  },
  importApp: (req: ComposioCatalogImportRequest) =>
    ApiClient.post<ComposioCatalogImportResponse>(CATALOG_BASE + '/import', req),
};
