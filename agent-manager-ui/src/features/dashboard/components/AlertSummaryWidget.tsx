import React from 'react';
import { Link } from 'react-router-dom';
import { LuShieldAlert, LuCircleCheck, LuTriangleAlert, LuInfo, LuX } from 'react-icons/lu';
import { useActiveAlerts, useAcknowledgeAlert } from '../../alerts/api/alertsApi';
import type { AlertEvent } from '../../alerts/api/alertsApi';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';

const SEVERITY_CONFIG = {
    CRITICAL: { variant: 'error' as const, icon: LuShieldAlert, label: 'Critical' },
    WARNING:  { variant: 'warning' as const, icon: LuTriangleAlert, label: 'Warning' },
    INFO:     { variant: 'info' as const, icon: LuInfo, label: 'Info' },
};

const getSeverityConfig = (severity: string) =>
    SEVERITY_CONFIG[severity.toUpperCase() as keyof typeof SEVERITY_CONFIG] ?? SEVERITY_CONFIG.INFO;

const AlertRow: React.FC<{ event: AlertEvent; onAck: (id: string) => void }> = ({ event, onAck }) => {
    const cfg = getSeverityConfig(event.severity);
    const Icon = cfg.icon;

    return (
        <div className="flex items-start gap-3 p-3 rounded-lg bg-obsidian-base/40 border border-obsidian-stroke/20">
            <div className="mt-0.5 shrink-0">
                <Icon className={`h-4 w-4 ${cfg.variant === 'error' ? 'text-error' : cfg.variant === 'warning' ? 'text-warn-amber' : 'text-info-sky'}`} />
            </div>
            <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-0.5">
                    <Badge variant={cfg.variant} size="sm" outline={false} className="uppercase text-[10px] font-bold tracking-wider">
                        {cfg.label}
                    </Badge>
                    <span className="text-[10px] text-theme-muted font-mono">
                        {new Date(event.firedAt).toLocaleTimeString()}
                    </span>
                </div>
                <p className="text-sm text-theme-foreground truncate">{event.message}</p>
            </div>
            <Button
                size="sm"
                variant="ghost"
                className="shrink-0 btn-square text-theme-muted hover:text-white hover:bg-obsidian-stroke/50"
                onClick={() => onAck(event.id)}
                title="Acknowledge"
            >
                <LuX className="h-3.5 w-3.5" />
            </Button>
        </div>
    );
};

export const AlertSummaryWidget: React.FC = () => {
    const { data: alerts = [], isLoading } = useActiveAlerts();
    const { mutate: acknowledge } = useAcknowledgeAlert();

    const criticalCount = alerts.filter(a => a.severity.toUpperCase() === 'CRITICAL').length;
    const warningCount  = alerts.filter(a => a.severity.toUpperCase() === 'WARNING').length;

    if (isLoading) return null;

    if (alerts.length === 0) {
        return (
            <div className="flex items-center gap-3 p-4 mb-6 rounded-lg bg-active-green/5 border border-active-green/20 text-active-green">
                <LuCircleCheck className="h-5 w-5 shrink-0" />
                <span className="text-sm font-medium">All Clear — no active alerts</span>
            </div>
        );
    }

    return (
        <div className="mb-6">
            <div className="flex justify-between items-center mb-3">
                <div className="flex items-center gap-3">
                    <h3 className="font-bold text-sm uppercase tracking-wider text-theme-foreground">Active Alerts</h3>
                    {criticalCount > 0 && (
                        <Badge variant="error" size="sm" outline={false}>{criticalCount} critical</Badge>
                    )}
                    {warningCount > 0 && (
                        <Badge variant="warning" size="sm" outline={false}>{warningCount} warning</Badge>
                    )}
                </div>
                <Link to="/admin/alert-integrations" className="text-xs text-theme-muted hover:text-agent-blue transition-colors">
                    View All →
                </Link>
            </div>
            <div className="flex flex-col gap-2">
                {alerts.slice(0, 5).map(event => (
                    <AlertRow key={event.id} event={event} onAck={acknowledge} />
                ))}
                {alerts.length > 5 && (
                    <p className="text-xs text-theme-muted text-center py-1">
                        +{alerts.length - 5} more — <Link to="/admin/alert-integrations" className="hover:text-agent-blue">view all</Link>
                    </p>
                )}
            </div>
        </div>
    );
};
