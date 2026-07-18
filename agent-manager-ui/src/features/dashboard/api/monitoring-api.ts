import { ApiClient } from '../../../shared/api/client';

export interface AgentStats {
  agentId: string;
  agentName: string;
  totalRuns: number;
  activeRuns: number;
  errorRuns: number;
  lastRunAt: string | null;
}

export interface GlobalStats {
  totalAgents: number;
  totalActiveRuns: number;
  totalCompletedRuns: number;
  agentStats: AgentStats[];
}

export class MonitoringApi {
  static async getStats(): Promise<GlobalStats> {
    return ApiClient.get<GlobalStats>('/monitoring/stats');
  }
}
