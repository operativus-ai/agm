import { ApiClient } from '../../../shared/api/client';
import type {
  OrgRoutingConfigRequest,
  OrgRoutingConfigResponse,
  RoutingEmbeddingsBackfillResponse,
} from '../types/routing-config.types';

const BASE = '/v1/routing-config';
const EMBEDDINGS_BASE = '/v1/admin/routing-embeddings';

export class RoutingConfigApi {
  static async get(): Promise<OrgRoutingConfigResponse> {
    return ApiClient.get<OrgRoutingConfigResponse>(BASE);
  }

  static async upsert(req: OrgRoutingConfigRequest): Promise<OrgRoutingConfigResponse> {
    return ApiClient.put<OrgRoutingConfigResponse>(BASE, req);
  }

  static async remove(): Promise<void> {
    return ApiClient.delete<void>(BASE);
  }

  /** Eager-populates routing_vectors for the caller's org (semantic scoring). */
  static async backfillEmbeddings(): Promise<RoutingEmbeddingsBackfillResponse> {
    return ApiClient.post<RoutingEmbeddingsBackfillResponse>(`${EMBEDDINGS_BASE}/backfill`);
  }
}
