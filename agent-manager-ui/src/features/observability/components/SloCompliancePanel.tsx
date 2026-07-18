import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { LuCircleCheck, LuCircleX, LuRefreshCw } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { sloApi, type SloStatus } from '../api/sloApi';

const formatValue = (v: number, unit: string): string => {
    if (!Number.isFinite(v)) return '—';
    if (unit === '%' || unit === 'percent' || unit === 'percentage') return `${v.toFixed(2)}%`;
    if (unit === 'ms') return `${v.toFixed(0)}ms`;
    if (unit === 's' || unit === 'seconds') return `${v.toFixed(2)}s`;
    return `${v}${unit ? ` ${unit}` : ''}`;
};

const compliancePercent = (slo: SloStatus): number | null => {
    if (!Number.isFinite(slo.target) || !Number.isFinite(slo.current) || slo.target === 0) {
        return null;
    }
    // Generic ratio for at-a-glance bar; not always meaningful (e.g. latency targets
    // where lower is better) — treat as a directional indicator only.
    return Math.min(100, Math.max(0, (slo.current / slo.target) * 100));
};

const SloRow: React.FC<{ slo: SloStatus }> = ({ slo }) => {
    const Icon = slo.compliant ? LuCircleCheck : LuCircleX;
    const tone = slo.compliant ? 'text-success' : 'text-error';
    const pct = compliancePercent(slo);

    return (
        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-5 py-4">
            <div className="flex items-start justify-between gap-3">
                <div className="flex items-start gap-3 min-w-0">
                    <Icon className={`w-5 h-5 mt-0.5 shrink-0 ${tone}`} />
                    <div className="min-w-0">
                        <div className="text-sm font-medium text-(--theme-foreground) break-words">
                            {slo.sloName}
                        </div>
                        <div className="mt-1 flex items-baseline gap-3 text-xs text-(--theme-muted)">
                            <span>
                                Current <span className="font-mono text-(--theme-foreground)">{formatValue(slo.current, slo.unit)}</span>
                            </span>
                            <span>
                                Target <span className="font-mono">{formatValue(slo.target, slo.unit)}</span>
                            </span>
                        </div>
                    </div>
                </div>
                <Badge variant={slo.compliant ? 'success' : 'error'} className="text-xs shrink-0">
                    {slo.compliant ? 'COMPLIANT' : 'BREACHED'}
                </Badge>
            </div>
            {pct != null && (
                <div className="mt-3 h-1.5 bg-(--theme-muted)/10 rounded-full overflow-hidden">
                    <div
                        className={`h-full ${slo.compliant ? 'bg-success' : 'bg-error'}`}
                        style={{ width: `${pct}%` }}
                    />
                </div>
            )}
        </div>
    );
};

export const SloCompliancePanel: React.FC = () => {
    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['observability', 'slo-status'],
        queryFn: () => sloApi.getStatus(),
        staleTime: 30_000,
        refetchInterval: 60_000,
    });

    if (error) {
        return (
            <Alert severity="error" title="Failed to load SLO compliance">
                {(error as Error).message}
            </Alert>
        );
    }

    const slos = data ?? [];
    const breached = slos.filter(s => !s.compliant).length;

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between">
                <div className="text-xs text-(--theme-muted)">
                    {isLoading
                        ? 'Loading…'
                        : `${slos.length} SLO${slos.length === 1 ? '' : 's'} tracked${breached > 0 ? ` · ${breached} breached` : ''}`}
                </div>
                <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching} className="gap-2">
                    {isFetching ? <span className="loading loading-spinner loading-sm" /> : <LuRefreshCw className="w-4 h-4" />}
                    Refresh
                </Button>
            </div>

            {isLoading && slos.length === 0 ? (
                <div className="space-y-2">
                    {[1, 2, 3].map(i => (
                        <div key={i} className="h-20 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
                    ))}
                </div>
            ) : slos.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                    No SLOs are currently being tracked.
                </div>
            ) : (
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
                    {slos.map(slo => (
                        <SloRow key={slo.sloName} slo={slo} />
                    ))}
                </div>
            )}
        </div>
    );
};
