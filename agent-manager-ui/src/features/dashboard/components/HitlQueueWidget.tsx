import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { LuShieldCheck, LuChevronRight, LuTriangleAlert } from 'react-icons/lu';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import type { Approval } from '../../../shared/types/orchestration';
import { Badge } from '../../../shared/components/ui/Badge';

/**
 * HITL Queue dashboard widget — surfaces the count of pending Human-in-the-Loop approvals
 * blocking agent runs. Polls /api/v1/approvals/pending every 30s. Shows the count, the age
 * of the oldest pending approval, and a CTA to the full /approvals page.
 *
 * Severity bands (matches the convention pinned by ApprovalsPage's getAgeBadge):
 *   - 0 pending    → success (calm)
 *   - 1+ < 8h      → info (informational)
 *   - 1+ ≥ 8h      → warning (operator should look)
 *   - 1+ ≥ 20h     → error (SLA breach — same threshold as ApprovalService.checkApprovalSla)
 */

const formatAge = (createdAt?: string): { text: string; severity: 'ok' | 'info' | 'warn' | 'error' } => {
    if (!createdAt) return { text: '—', severity: 'info' };
    const hours = (Date.now() - new Date(createdAt).getTime()) / 3_600_000;
    if (hours >= 20) return { text: `${Math.floor(hours)}h`, severity: 'error' };
    if (hours >= 8) return { text: `${Math.floor(hours)}h`, severity: 'warn' };
    if (hours >= 1) return { text: `${Math.floor(hours)}h`, severity: 'info' };
    const mins = Math.floor(hours * 60);
    return { text: `${Math.max(mins, 1)}m`, severity: 'info' };
};

export const HitlQueueWidget: React.FC = () => {
    const [count, setCount] = useState<number | null>(null);
    const [oldestAge, setOldestAge] = useState<{ text: string; severity: 'ok' | 'info' | 'warn' | 'error' }>({ text: '—', severity: 'info' });
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let cancelled = false;

        const fetchPending = async () => {
            try {
                // size=1 — we only need the totalElements count + the oldest row's createdAt.
                // ApprovalsController.getPendingApprovals returns a Spring Page; default sort
                // is by createdAt — confirm vs ApprovalsPage which uses the same endpoint.
                const page = await orchestrationApi.getApprovals({ status: 'PENDING', page: 0, size: 1 });
                if (cancelled) return;
                setCount(page.page.totalElements ?? 0);
                const oldest = page.content?.[0] as Approval | undefined;
                setOldestAge(formatAge(oldest?.createdAt));
            } catch {
                if (cancelled) return;
                setCount(null);
            } finally {
                if (!cancelled) setLoading(false);
            }
        };

        fetchPending();
        const id = setInterval(fetchPending, 30_000);
        return () => {
            cancelled = true;
            clearInterval(id);
        };
    }, []);

    const severity = count == null ? 'info' : count === 0 ? 'ok' : oldestAge.severity;

    const severityClass = (s: typeof severity) => {
        switch (s) {
            case 'error': return 'border-error/40 bg-error/5';
            case 'warn': return 'border-warn-amber/40 bg-warn-amber/5';
            case 'ok': return 'border-active-green/30 bg-active-green/5';
            default: return 'border-obsidian-stroke';
        }
    };

    const iconColor = (s: typeof severity) => {
        switch (s) {
            case 'error': return 'text-error';
            case 'warn': return 'text-warn-amber';
            case 'ok': return 'text-active-green';
            default: return 'text-info-sky';
        }
    };

    return (
        <Link
            to="/approvals"
            className={`block rounded-xl border ${severityClass(severity)} p-5 transition-all hover:shadow-md hover:border-(--theme-foreground)/20 group`}
        >
            <div className="flex items-start justify-between gap-3 mb-3">
                <div className="flex items-center gap-2">
                    <LuShieldCheck className={`w-4 h-4 ${iconColor(severity)}`} />
                    <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted)">HITL Queue</h3>
                </div>
                <LuChevronRight className="w-4 h-4 text-(--theme-muted) group-hover:text-(--theme-foreground) transition-colors" />
            </div>

            {loading ? (
                <div className="h-12 bg-obsidian-elevated/40 rounded animate-pulse" />
            ) : count == null ? (
                <div className="text-sm text-error opacity-70">Failed to load</div>
            ) : count === 0 ? (
                <div className="space-y-1">
                    <div className="text-2xl font-bold font-mono text-active-green">0</div>
                    <p className="text-xs text-(--theme-muted)">No approvals pending</p>
                </div>
            ) : (
                <div className="space-y-2">
                    <div className="flex items-baseline gap-3">
                        <span className={`text-3xl font-bold font-mono ${iconColor(severity)}`}>{count}</span>
                        <span className="text-xs text-(--theme-muted) uppercase tracking-wider">pending</span>
                    </div>
                    <div className="flex items-center gap-2">
                        <span className="text-[11px] text-(--theme-muted)">Oldest</span>
                        <Badge
                            variant={severity === 'error' ? 'error' : severity === 'warn' ? 'warning' : 'info'}
                            size="sm"
                            outline
                            className="font-mono text-[10px]"
                        >
                            {oldestAge.text}
                        </Badge>
                        {severity === 'error' && (
                            <span className="flex items-center gap-1 text-[10px] text-error font-medium uppercase tracking-wider">
                                <LuTriangleAlert className="w-3 h-3" />
                                SLA breach
                            </span>
                        )}
                    </div>
                </div>
            )}

            <p className="text-[10px] text-(--theme-muted) opacity-60 mt-3">Updates every 30 seconds</p>
        </Link>
    );
};
