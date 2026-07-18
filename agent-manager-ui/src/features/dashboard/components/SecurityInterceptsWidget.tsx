import React, { useEffect, useState } from 'react';
import { LuShieldCheck, LuBan, LuUserX } from 'react-icons/lu';
import { ApiClient } from '../../../shared/api/client';

/**
 * Security Intercepts dashboard widget — surfaces cumulative counts from the two safety
 * advisors instrumented this session: PII redactions (PIIAnonymizationAdvisor) and prompt
 * injection blocks (PromptInjectionAdvisor). Polls /api/v1/observability/security-intercepts
 * every 30s.
 *
 * Counts are cumulative since process start. For time-windowed views (e.g. "today"), the
 * production path is Prometheus + Grafana with rate() — this widget exists for at-a-glance
 * "is the safety chain firing?" awareness.
 */

interface SecurityInterceptsResponse {
    piiScannedClean: number;
    piiScannedRedacted: number;
    piiRedactionEvents: number;
    promptInjectionOk: number;
    promptInjectionBlocked: number;
}

type StatVariant = 'neutral' | 'warn' | 'danger';

const StatTile: React.FC<{
    icon: React.ReactNode;
    label: string;
    value: number;
    sublabel?: string;
    variant?: StatVariant;
}> = ({ icon, label, value, sublabel, variant = 'neutral' }) => {
    const valueColor =
        variant === 'danger' ? 'text-error' :
        variant === 'warn' ? 'text-warn-amber' :
        'text-(--theme-foreground)';
    const iconBg =
        variant === 'danger' ? 'bg-error/10 text-error' :
        variant === 'warn' ? 'bg-warn-amber/10 text-warn-amber' :
        'bg-obsidian-elevated text-(--theme-muted)';

    return (
        <div className="flex items-center gap-3 p-3 rounded-lg bg-obsidian-base/40 border border-obsidian-stroke/30">
            <div className={`p-2 rounded-md ${iconBg}`}>
                {icon}
            </div>
            <div className="flex-1 min-w-0">
                <div className={`text-2xl font-bold font-mono leading-none ${valueColor}`}>
                    {value.toLocaleString()}
                </div>
                <div className="text-[11px] text-(--theme-muted) uppercase tracking-wider mt-1 font-bold">
                    {label}
                </div>
                {sublabel && (
                    <div className="text-[10px] text-(--theme-muted) opacity-60 mt-0.5">{sublabel}</div>
                )}
            </div>
        </div>
    );
};

export const SecurityInterceptsWidget: React.FC = () => {
    const [data, setData] = useState<SecurityInterceptsResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);

    useEffect(() => {
        let cancelled = false;

        const fetchCounts = async () => {
            try {
                const response = await ApiClient.get<SecurityInterceptsResponse>('/v1/observability/security-intercepts');
                if (cancelled) return;
                setData(response);
                setError(false);
            } catch {
                if (cancelled) return;
                setError(true);
            } finally {
                if (!cancelled) setLoading(false);
            }
        };

        fetchCounts();
        const id = setInterval(fetchCounts, 30_000);
        return () => {
            cancelled = true;
            clearInterval(id);
        };
    }, []);

    if (error) {
        return (
            <div className="rounded-xl border border-obsidian-stroke bg-(--theme-card) p-5 mb-6">
                <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted) mb-3">Security Intercepts</h3>
                <p className="text-sm text-(--theme-muted) opacity-70">Unable to load counts (admin role required).</p>
            </div>
        );
    }

    return (
        <div className="rounded-xl border border-obsidian-stroke bg-(--theme-card) p-5 mb-6">
            <div className="flex items-baseline justify-between mb-4">
                <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted)">Security Intercepts</h3>
                <span className="text-[10px] text-(--theme-muted) opacity-60">Updates every 30 seconds</span>
            </div>

            {loading || !data ? (
                <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                    {[1, 2, 3].map(i => <div key={i} className="h-20 bg-obsidian-elevated/40 rounded-lg animate-pulse" />)}
                </div>
            ) : (
                <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                    <StatTile
                        icon={<LuUserX className="w-4 h-4" />}
                        label="PII Redactions"
                        value={data.piiRedactionEvents}
                        sublabel={`${data.piiScannedRedacted} requests touched`}
                        variant={data.piiRedactionEvents > 0 ? 'warn' : 'neutral'}
                    />
                    <StatTile
                        icon={<LuBan className="w-4 h-4" />}
                        label="Injections Blocked"
                        value={data.promptInjectionBlocked}
                        sublabel={`${data.promptInjectionOk + data.promptInjectionBlocked} prompts scanned`}
                        variant={data.promptInjectionBlocked > 0 ? 'danger' : 'neutral'}
                    />
                    <StatTile
                        icon={<LuShieldCheck className="w-4 h-4" />}
                        label="Clean Scans"
                        value={data.piiScannedClean + data.promptInjectionOk}
                        sublabel="No threats detected"
                    />
                </div>
            )}
        </div>
    );
};
