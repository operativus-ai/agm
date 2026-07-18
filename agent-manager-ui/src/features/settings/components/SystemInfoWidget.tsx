import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { LuServer, LuCircleCheck, LuNetwork, LuBookOpen } from 'react-icons/lu';
import { MonitoringApi } from '../../dashboard/api/monitoring-api';
import { KnowledgeBasesApi } from '../../knowledge/api/knowledge-bases-api';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';

export const SystemInfoWidget: React.FC = () => {
    const { data: monitoringStats } = useQuery({
        queryKey: ['monitoring', 'stats'],
        queryFn: MonitoringApi.getStats,
        staleTime: 30000,
    });

    const { data: knowledgeBases = [] } = useQuery({
        queryKey: ['knowledge-bases'],
        queryFn: KnowledgeBasesApi.getAll,
        staleTime: 60000,
    });

    const { data: teamsPage } = useQuery({
        queryKey: ['teams', 'summary'],
        queryFn: () => orchestrationApi.getTeams({ page: 0, size: 1 }),
        staleTime: 60000,
    });

    const agentCount = monitoringStats?.totalAgents ?? 0;
    const kbCount = knowledgeBases.length;
    const teamCount = teamsPage?.page.totalElements ?? 0;

    return (
        <div className="card bg-(--obsidian-surface) shadow-xl border border-obsidian-stroke/50 mb-8 overflow-hidden">
            <div className="bg-linear-to-r from-(--obsidian-surface) to-(--obsidian-elevated) p-4 border-b border-obsidian-stroke/50 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                <div>
                    <h2 className="card-title font-mono text-sm uppercase tracking-wider text-(--agent-blue)">Procurator Footprint</h2>
                    <div className="text-xs text-base-content/60 font-mono mt-1">
                        Runtime telemetry from live service endpoints
                    </div>
                </div>
                <div className="badge badge-primary badge-outline font-mono shadow-sm">
                    AgentManager v2.0
                </div>
            </div>

            <div className="grid grid-cols-2 md:grid-cols-4 divide-x divide-y md:divide-y-0 divide-obsidian-stroke/30 bg-(--obsidian-base)/20">
                <div className="p-4 flex items-center justify-between">
                    <div>
                        <div className="text-xs uppercase font-bold text-base-content/50 mb-1">Agents Loaded</div>
                        <div className="text-xl font-bold font-mono text-base-content">{agentCount}</div>
                    </div>
                    <div className="p-3 bg-(--agent-blue)/10 rounded-xl text-(--agent-blue)">
                        <LuServer size={20} />
                    </div>
                </div>

                <div className="p-4 flex items-center justify-between">
                    <div>
                        <div className="text-xs uppercase font-bold text-base-content/50 mb-1">Knowledge Bases</div>
                        <div className="text-xl font-bold font-mono text-base-content">{kbCount}</div>
                    </div>
                    <div className="p-3 bg-(--info-sky)/10 rounded-xl text-(--info-sky)">
                        <LuBookOpen size={20} />
                    </div>
                </div>

                <div className="p-4 flex items-center justify-between">
                    <div>
                        <div className="text-xs uppercase font-bold text-base-content/50 mb-1">Native Teams</div>
                        <div className="text-xl font-bold font-mono text-base-content">{teamCount}</div>
                    </div>
                    <div className="p-3 bg-(--warn-amber)/10 rounded-xl text-(--warn-amber)">
                        <LuNetwork size={20} />
                    </div>
                </div>

                <div className="p-4 flex items-center justify-between">
                    <div>
                        <div className="text-xs uppercase font-bold text-base-content/50 mb-1">Status</div>
                        <div className="text-xl font-bold font-mono text-success">ONLINE</div>
                    </div>
                    <div className="p-3 bg-success/10 rounded-xl text-success">
                        <LuCircleCheck size={20} />
                    </div>
                </div>
            </div>
        </div>
    );
};
