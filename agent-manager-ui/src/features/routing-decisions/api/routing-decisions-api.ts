import { ApiClient } from '../../../shared/api/client';
import type { RoutingDecisionsPage, RoutingDecisionResponse } from '../types/routing-decisions.types';

const BASE = '/v1/admin/routing-decisions';

export interface ListParams {
  strategy?: RoutingDecisionResponse['strategyUsed'];
  page?: number;
  size?: number;
}

export class RoutingDecisionsApi {
  static async list(params: ListParams = {}): Promise<RoutingDecisionsPage> {
    const query = new URLSearchParams();
    if (params.strategy) query.set('strategy', params.strategy);
    if (params.page != null) query.set('page', String(params.page));
    if (params.size != null) query.set('size', String(params.size));
    const qs = query.toString();
    return ApiClient.get<RoutingDecisionsPage>(qs ? `${BASE}?${qs}` : BASE);
  }

  static async get(id: string): Promise<RoutingDecisionResponse> {
    return ApiClient.get<RoutingDecisionResponse>(`${BASE}/${id}`);
  }
}
