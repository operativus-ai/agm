import React, { useEffect, useState } from 'react';
import { LuRefreshCw } from 'react-icons/lu';
import { evaluationApi, type AggregateEvalMetrics } from '../../../shared/api/evaluationApi';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';

const formatLatency = (ms: number): string => {
    if (!Number.isFinite(ms) || ms <= 0) return '—';
    if (ms < 1000) return `${Math.round(ms)}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
};

const passRateTone = (rate: number): string => {
    if (rate >= 90) return 'text-success';
    if (rate >= 70) return 'text-warning';
    return 'text-error';
};

export const EvaluationMetricsPanel: React.FC = () => {
    const [metrics, setMetrics] = useState<AggregateEvalMetrics | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const load = async () => {
        setLoading(true);
        setError(null);
        try {
            const data = await evaluationApi.getMetrics();
            setMetrics(data);
        } catch (err) {
            setError((err as Error).message || 'Failed to load metrics.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        load();
    }, []);

    if (error) {
        return (
            <Alert severity="error" title="Failed to load metrics">{error}</Alert>
        );
    }

    if (!metrics && loading) {
        return (
            <div className="grid grid-cols-2 lg:grid-cols-5 gap-3">
                {[1, 2, 3, 4, 5].map(i => (
                    <div key={i} className="h-20 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
                ))}
            </div>
        );
    }

    if (!metrics) return null;

    return (
        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-5 py-4 space-y-3">
            <div className="flex items-center justify-between">
                <div className="text-xs font-medium text-(--theme-foreground) uppercase tracking-wider">
                    Aggregate metrics
                </div>
                <Button
                    variant="ghost"
                    size="sm"
                    className="gap-1.5"
                    onClick={load}
                    disabled={loading}
                >
                    {loading
                        ? <span className="loading loading-spinner loading-xs" />
                        : <LuRefreshCw className="w-3.5 h-3.5" />}
                    Refresh
                </Button>
            </div>

            <div className="grid grid-cols-2 lg:grid-cols-5 gap-3">
                <Kpi label="Total runs" value={metrics.totalRuns.toLocaleString()} />
                <Kpi label="Cases" value={metrics.totalCases.toLocaleString()} subtitle={`${metrics.passedCases.toLocaleString()} passed · ${metrics.failedCases.toLocaleString()} failed`} />
                <Kpi label="Pass rate" value={`${metrics.passRate.toFixed(1)}%`} valueClassName={passRateTone(metrics.passRate)} />
                <Kpi label="Avg score" value={metrics.averageScore.toFixed(2)} />
                <Kpi label="Avg latency" value={formatLatency(metrics.averageLatencyMs)} />
            </div>
        </div>
    );
};

const Kpi: React.FC<{
    label: string;
    value: string;
    subtitle?: string;
    valueClassName?: string;
}> = ({ label, value, subtitle, valueClassName }) => (
    <div className="bg-obsidian-elevated/30 border border-(--theme-muted)/5 rounded-lg px-4 py-3">
        <div className="text-[11px] uppercase tracking-wider text-(--theme-muted)">{label}</div>
        <div className={`text-2xl font-semibold mt-0.5 ${valueClassName ?? 'text-(--theme-foreground)'}`}>{value}</div>
        {subtitle && <div className="text-[11px] text-(--theme-muted) mt-1">{subtitle}</div>}
    </div>
);
