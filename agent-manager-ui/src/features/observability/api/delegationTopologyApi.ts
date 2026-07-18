import { ApiClient } from '../../../shared/api/client';

const BASE = '/v1/observability/aggregates';

export interface DelegationEdge {
  from: string;
  to: string;
  strategy: string;
  count: number;
}

export interface DelegationNode {
  agentId: string;
  totalIn: number;
  totalOut: number;
}

export interface DelegationTopologyResponse {
  edges: DelegationEdge[];
  nodes: DelegationNode[];
}

export const delegationTopologyApi = {
  get: (window = 30) => {
    const sp = new URLSearchParams();
    sp.set('window', String(window));
    return ApiClient.get<DelegationTopologyResponse>(`${BASE}/delegation-topology?${sp.toString()}`);
  },
};
