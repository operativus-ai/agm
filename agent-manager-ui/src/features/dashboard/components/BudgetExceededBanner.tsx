import React, { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { LuDollarSign, LuX } from 'react-icons/lu';
import { budgetExceededApi, type BudgetExceededEvent } from '../../alerts/api/budgetExceededApi';

const POLL_INTERVAL_MS = 60_000;
const ACK_STORAGE_KEY = 'agm.budget-exceeded.ackUpToEventId';

const readAck = (): number => {
    try {
        const raw = localStorage.getItem(ACK_STORAGE_KEY);
        if (!raw) return 0;
        const n = Number(raw);
        return Number.isFinite(n) ? n : 0;
    } catch {
        return 0;
    }
};

const writeAck = (id: number): void => {
    try {
        localStorage.setItem(ACK_STORAGE_KEY, String(id));
    } catch {
        /* ignore */
    }
};

const formatPayloadSummary = (e: BudgetExceededEvent): string => {
    const p = (e.payload ?? {}) as Record<string, unknown>;
    const limit = p.limit ?? p.budgetUsd;
    const actual = p.actual ?? p.actualUsd ?? p.totalCostUsd;
    if (limit && actual) return `$${actual} / $${limit}`;
    if (limit) return `limit $${limit}`;
    return '';
};

export const BudgetExceededBanner: React.FC = () => {
    const [events, setEvents] = useState<BudgetExceededEvent[]>([]);
    const [ackUpTo, setAckUpTo] = useState<number>(() => readAck());
    const cursorRef = useRef<string | undefined>(undefined);

    useEffect(() => {
        let cancelled = false;
        let timer: ReturnType<typeof setInterval> | null = null;

        const fetchOnce = async () => {
            try {
                const res = await budgetExceededApi.getFeed(cursorRef.current);
                if (cancelled) return;
                cursorRef.current = res.nextCursor;
                if (res.events.length > 0) {
                    setEvents(prev => {
                        const seen = new Set(prev.map(e => e.id));
                        const merged = [...prev];
                        for (const ev of res.events) {
                            if (!seen.has(ev.id)) merged.push(ev);
                        }
                        return merged;
                    });
                }
            } catch {
                // Banner is best-effort; failures are silent.
            }
        };

        fetchOnce();
        timer = setInterval(fetchOnce, POLL_INTERVAL_MS);

        return () => {
            cancelled = true;
            if (timer) clearInterval(timer);
        };
    }, []);

    const unacknowledged = events.filter(e => e.id > ackUpTo);
    if (unacknowledged.length === 0) return null;

    const latest = unacknowledged[unacknowledged.length - 1];
    const dismissAll = () => {
        const newest = events.reduce((max, e) => (e.id > max ? e.id : max), 0);
        writeAck(newest);
        setAckUpTo(newest);
    };

    return (
        <div className="bg-error/10 border border-error/30 rounded-xl px-4 py-3 flex items-center gap-3">
            <LuDollarSign className="w-5 h-5 text-error shrink-0" />
            <div className="flex-1 min-w-0">
                <div className="text-sm font-medium text-(--theme-foreground)">
                    {unacknowledged.length === 1
                        ? '1 budget-exceeded event'
                        : `${unacknowledged.length} budget-exceeded events`}
                    {' — latest on '}
                    <Link to={`/runs/${latest.runId}`} className="font-mono hover:underline">
                        {latest.runId}
                    </Link>
                    {(() => {
                        const summary = formatPayloadSummary(latest);
                        return summary ? <span className="text-(--theme-muted)"> ({summary})</span> : null;
                    })()}
                </div>
                <div className="text-xs text-(--theme-muted) mt-0.5">
                    Checked every minute · <Link to="/alerts/budget-exceeded" className="hover:underline">View all</Link>
                </div>
            </div>
            <button
                type="button"
                onClick={dismissAll}
                className="text-(--theme-muted) hover:text-(--theme-foreground) p-1"
                title="Acknowledge all"
            >
                <LuX className="w-4 h-4" />
            </button>
        </div>
    );
};
