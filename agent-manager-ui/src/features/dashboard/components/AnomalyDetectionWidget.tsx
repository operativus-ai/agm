import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { LuShieldCheck, LuZap } from 'react-icons/lu';
import { ApiClient } from '../../../shared/api/client';

interface ActiveAnomaly {
    sessionId: string;
    agentId: string;
    burnRateUsdPerHour: number;
    baselineUsdPerHour: number;
    anomalyRatio: number;
}

const useActiveAnomalies = () =>
    useQuery<ActiveAnomaly[]>({
        queryKey: ['finops', 'anomalies', 'active'],
        queryFn: () => ApiClient.get<ActiveAnomaly[]>('/v1/finops/anomalies/active'),
        refetchInterval: 15_000,
        staleTime: 10_000,
    });

export const AnomalyDetectionWidget: React.FC = () => {
    const { data: anomalies = [], isLoading } = useActiveAnomalies();

    if (isLoading) return null;

    if (anomalies.length === 0) {
        return (
            <div className="flex items-center gap-3 p-4 mb-6 rounded-lg bg-active-green/5 border border-active-green/20 text-active-green">
                <LuShieldCheck className="h-5 w-5 shrink-0" />
                <span className="text-sm font-medium">No burn-rate anomalies detected</span>
            </div>
        );
    }

    return (
        <div className="mb-6">
            <div className="flex items-center gap-2 mb-3">
                <LuZap className="h-4 w-4 text-warn-amber" />
                <h3 className="font-bold text-sm uppercase tracking-wider text-theme-foreground">Burn-Rate Anomalies</h3>
                <span className="badge badge-warning badge-sm font-mono">{anomalies.length}</span>
            </div>
            <div className="overflow-x-auto rounded-lg border border-obsidian-stroke/30">
                <table className="table table-sm w-full">
                    <thead>
                        <tr className="text-[10px] uppercase tracking-widest text-theme-muted border-b border-obsidian-stroke/30">
                            <th className="bg-obsidian-base/60 font-bold">Agent</th>
                            <th className="bg-obsidian-base/60 font-bold">Session</th>
                            <th className="bg-obsidian-base/60 font-bold text-right">Burn Rate</th>
                            <th className="bg-obsidian-base/60 font-bold text-right">Baseline</th>
                            <th className="bg-obsidian-base/60 font-bold text-right">Ratio</th>
                        </tr>
                    </thead>
                    <tbody>
                        {anomalies.map(a => {
                            const isExtreme = a.anomalyRatio > 10;
                            const ratioColor = isExtreme ? 'text-error' : 'text-warn-amber';
                            return (
                                <tr key={a.sessionId} className="border-b border-obsidian-stroke/10 hover:bg-obsidian-elevated/40">
                                    <td className="font-mono text-xs text-theme-foreground">{a.agentId}</td>
                                    <td className="font-mono text-xs text-theme-muted truncate max-w-[120px]">{a.sessionId}</td>
                                    <td className="font-mono text-xs text-right text-theme-foreground">${a.burnRateUsdPerHour.toFixed(3)}/hr</td>
                                    <td className="font-mono text-xs text-right text-theme-muted">${a.baselineUsdPerHour.toFixed(3)}/hr</td>
                                    <td className={`font-mono text-xs text-right font-bold ${ratioColor}`}>{a.anomalyRatio.toFixed(1)}×</td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            </div>
        </div>
    );
};
