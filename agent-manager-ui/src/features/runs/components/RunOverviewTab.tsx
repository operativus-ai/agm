import React from 'react';
import { Link } from 'react-router-dom';
import { Badge } from '../../../shared/components/ui/Badge';
import type { AgentRunResponse, RunStatus } from '../types/runs';

const statusVariant = (s: RunStatus): 'success' | 'error' | 'warning' | 'info' | 'ghost' => {
    if (s === 'COMPLETED' || s === 'APPROVED') return 'success';
    if (s === 'FAILED' || s === 'REJECTED' || s === 'EXPIRED' || s === 'CANCELLED') return 'error';
    if (s === 'RUNNING' || s === 'PROCESSING') return 'info';
    if (s === 'PAUSED' || s === 'QUEUED' || s === 'PENDING') return 'warning';
    return 'ghost';
};

const formatNum = (v: number | null | undefined): string =>
    (v == null ? '—' : v.toLocaleString());

const formatDuration = (ms: number | null | undefined): string => {
    if (ms == null) return '—';
    if (ms < 1000) return `${ms}ms`;
    const s = ms / 1000;
    return s < 60 ? `${s.toFixed(1)}s` : `${(s / 60).toFixed(1)}m`;
};

const formatCost = (v: AgentRunResponse['totalCostUsd']): string => {
    if (v === null || v === undefined) return '—';
    const n = typeof v === 'string' ? Number(v) : v;
    return Number.isFinite(n) ? `$${n.toFixed(4)}` : '—';
};

const formatRiskScore = (v: AgentRunResponse['safetyRiskScore']): string => {
    if (v === null || v === undefined) return '—';
    const n = typeof v === 'string' ? Number(v) : v;
    if (!Number.isFinite(n)) return '—';
    return `${(n * 100).toFixed(1)}%`;
};

const formatTimestamp = (s: string | null | undefined): string => {
    if (!s) return '—';
    const d = new Date(s);
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
};

interface FieldProps {
    label: string;
    children: React.ReactNode;
    monospace?: boolean;
}

const Field: React.FC<FieldProps> = ({ label, children, monospace }) => (
    <div className="flex flex-col gap-0.5 py-2 border-b border-(--theme-muted)/5 last:border-b-0">
        <span className="text-[11px] uppercase tracking-wider text-(--theme-muted)">{label}</span>
        <span className={monospace ? 'font-mono text-xs break-all' : 'text-sm'}>{children}</span>
    </div>
);

interface CardProps {
    title: string;
    children: React.ReactNode;
}

const Card: React.FC<CardProps> = ({ title, children }) => (
    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-5 py-3">
        <div className="text-xs font-medium text-(--theme-foreground) mb-1">{title}</div>
        {children}
    </div>
);

const NULL_PLACEHOLDER = <span className="text-(--theme-muted)">—</span>;

interface RunOverviewTabProps {
    run: AgentRunResponse;
}

export const RunOverviewTab: React.FC<RunOverviewTabProps> = ({ run }) => {
    return (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <Card title="Identifiers">
                <Field label="Run ID" monospace>{run.id}</Field>
                <Field label="Agent ID" monospace>
                    {run.agentId
                        ? <Link to={`/agents/${run.agentId}`} className="hover:underline">{run.agentId}</Link>
                        : NULL_PLACEHOLDER}
                </Field>
                <Field label="Session ID" monospace>
                    {run.sessionId
                        ? <Link to={`/sessions/${run.sessionId}`} className="hover:underline">{run.sessionId}</Link>
                        : NULL_PLACEHOLDER}
                </Field>
                <Field label="Parent Run" monospace>
                    {run.parentRunId
                        ? <Link to={`/runs/${run.parentRunId}`} className="hover:underline">{run.parentRunId}</Link>
                        : NULL_PLACEHOLDER}
                </Field>
                <Field label="User" monospace>{run.userId ?? NULL_PLACEHOLDER}</Field>
                <Field label="Org" monospace>{run.orgId ?? NULL_PLACEHOLDER}</Field>
            </Card>

            <Card title="Execution">
                <Field label="Status">
                    <Badge variant={statusVariant(run.status)} className="text-xs">{run.status}</Badge>
                </Field>
                <Field label="Model">{run.model ?? NULL_PLACEHOLDER}</Field>
                <Field label="Orchestration strategy">{run.orchestrationStrategy ?? NULL_PLACEHOLDER}</Field>
                <Field label="Error type">
                    {run.errorType
                        ? <Badge variant="error" className="text-xs">{run.errorType}</Badge>
                        : NULL_PLACEHOLDER}
                </Field>
            </Card>

            <Card title="Telemetry">
                <Field label="Input tokens">{formatNum(run.inputTokens)}</Field>
                <Field label="Output tokens">{formatNum(run.outputTokens)}</Field>
                <Field label="Duration">{formatDuration(run.durationMs)}</Field>
                <Field label="Total cost">{formatCost(run.totalCostUsd)}</Field>
                <Field label="Safety risk score">{formatRiskScore(run.safetyRiskScore)}</Field>
            </Card>

            <Card title="Timing">
                <Field label="Created">{formatTimestamp(run.createdAt)}</Field>
                <Field label="Last updated">{formatTimestamp(run.updatedAt)}</Field>
            </Card>
        </div>
    );
};
