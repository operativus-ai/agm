import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
    LuActivity,
    LuArrowRight,
    LuChevronDown,
    LuChevronRight,
    LuCircleAlert,
    LuCircleCheck,
    LuCircleX,
    LuDollarSign,
    LuMessageSquare,
    LuPlay,
    LuTriangleAlert,
    LuWrench,
} from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { ApiClient } from '../../../shared/api/client';
import {
    type AgentRunEventType,
    type RunEvent,
    TERMINAL_RUN_EVENT_TYPES,
} from '../types/runs';

const MAX_RECONNECT_ATTEMPTS = 10;

const backoffMs = (attempt: number): number => {
    const base = Math.min(30_000, 500 * 2 ** attempt);
    const jitter = Math.random() * 0.3 * base;
    return Math.round(base + jitter);
};

const eventIcon: Record<AgentRunEventType, React.ComponentType<{ className?: string }>> = {
    RUN_START: LuPlay,
    RUN_COMPLETE: LuCircleCheck,
    RUN_FAILED: LuCircleX,
    BUDGET_EXCEEDED: LuDollarSign,
    TOOL_INVOKED: LuWrench,
    TOOL_COMPLETED: LuWrench,
    DELEGATION_START: LuArrowRight,
    DELEGATION_COMPLETE: LuArrowRight,
    HANDOFF: LuArrowRight,
    ORCHESTRATOR_DECISION: LuActivity,
    LLM_REQUEST: LuMessageSquare,
    LLM_RESPONSE: LuMessageSquare,
};

const eventTone = (t: AgentRunEventType): string => {
    if (t === 'RUN_FAILED' || t === 'BUDGET_EXCEEDED') return 'text-error';
    if (t === 'RUN_COMPLETE') return 'text-success';
    if (t === 'TOOL_INVOKED' || t === 'TOOL_COMPLETED') return 'text-warning';
    if (t === 'DELEGATION_START' || t === 'DELEGATION_COMPLETE' || t === 'HANDOFF') return 'text-info';
    return 'text-(--theme-muted)';
};

const renderSummary = (e: RunEvent): React.ReactNode => {
    const p = (e.payload ?? {}) as Record<string, unknown>;
    const get = (key: string): string | null => {
        const v = p[key];
        return v == null ? null : String(v);
    };

    switch (e.eventType) {
        case 'TOOL_INVOKED': {
            const tool = get('toolName') ?? get('tool') ?? '—';
            return <>Invoking <span className="font-mono">{tool}</span></>;
        }
        case 'TOOL_COMPLETED': {
            const tool = get('toolName') ?? get('tool') ?? '—';
            const dur = get('durationMs');
            return (
                <>
                    Completed <span className="font-mono">{tool}</span>
                    {dur && <span className="text-(--theme-muted)"> ({dur}ms)</span>}
                </>
            );
        }
        case 'BUDGET_EXCEEDED': {
            const limit = get('limit') ?? get('budgetUsd');
            return <>Budget exceeded{limit && <span className="text-(--theme-muted)"> (limit ${limit})</span>}</>;
        }
        case 'DELEGATION_START': {
            const from = get('sourceAgentName') ?? get('sourceAgentId');
            const to = get('targetAgentName') ?? get('targetAgentId');
            const depth = e.orchestrationDepth ?? get('depth');
            return (
                <>
                    Delegating{from && <> from <span className="font-mono">{from}</span></>}
                    {to && <> → <span className="font-mono">{to}</span></>}
                    {depth != null && <span className="text-(--theme-muted)"> (depth {depth})</span>}
                </>
            );
        }
        case 'DELEGATION_COMPLETE': {
            const to = get('targetAgentName') ?? get('targetAgentId');
            const dur = get('durationMs');
            return (
                <>
                    Delegation complete{to && <> → <span className="font-mono">{to}</span></>}
                    {dur && <span className="text-(--theme-muted)"> ({dur}ms)</span>}
                </>
            );
        }
        case 'HANDOFF': {
            const from = get('sourceAgentName') ?? get('sourceAgentId');
            const to = get('targetAgentName') ?? get('targetAgentId');
            return (
                <>
                    Handoff{from && <> from <span className="font-mono">{from}</span></>}
                    {to && <> → <span className="font-mono">{to}</span></>}
                </>
            );
        }
        case 'ORCHESTRATOR_DECISION': {
            const strategy = get('strategy');
            const decision = get('decisionType') ?? get('decision');
            return (
                <>
                    {strategy && <Badge variant="info" className="text-xs mr-2">{strategy}</Badge>}
                    {decision ?? 'Orchestrator decision'}
                </>
            );
        }
        case 'LLM_REQUEST': {
            const model = get('model');
            const tokens = get('inputTokens');
            return (
                <>
                    LLM request{model && <> <span className="font-mono">{model}</span></>}
                    {tokens && <span className="text-(--theme-muted)"> ({tokens} tok)</span>}
                </>
            );
        }
        case 'LLM_RESPONSE': {
            const model = get('model');
            const tokens = get('outputTokens');
            return (
                <>
                    LLM response{model && <> <span className="font-mono">{model}</span></>}
                    {tokens && <span className="text-(--theme-muted)"> ({tokens} tok)</span>}
                </>
            );
        }
        case 'RUN_START':
            return 'Run started';
        case 'RUN_COMPLETE':
            return 'Run complete';
        case 'RUN_FAILED':
            return <>Run failed{get('errorType') && <Badge variant="error" className="text-xs ml-2">{get('errorType')}</Badge>}</>;
    }
};

interface EventRowProps {
    event: RunEvent;
    /** When true, show the run id — useful in the multi-run (per-agent) view. */
    showRunId?: boolean;
    /** When true, show the agent id — useful in the multi-agent (org-wide) view. */
    showAgentId?: boolean;
}

const EventRow: React.FC<EventRowProps> = ({ event, showRunId, showAgentId }) => {
    const [expanded, setExpanded] = useState(false);
    const Icon = eventIcon[event.eventType] ?? LuActivity;
    const hasPayload = event.payload && Object.keys(event.payload).length > 0;
    const Chevron = expanded ? LuChevronDown : LuChevronRight;
    const isBudget = event.eventType === 'BUDGET_EXCEEDED';
    const isFailure = event.eventType === 'RUN_FAILED';

    return (
        <div className={`border-b border-(--theme-muted)/5 last:border-b-0 ${isBudget || isFailure ? 'bg-error/5' : ''}`}>
            <div
                className={`flex items-start gap-3 px-4 py-2.5 ${hasPayload ? 'cursor-pointer hover:bg-(--theme-muted)/5' : ''} transition-colors`}
                onClick={hasPayload ? () => setExpanded(!expanded) : undefined}
            >
                <div className="pt-0.5">
                    <Icon className={`w-4 h-4 ${eventTone(event.eventType)}`} />
                </div>
                <div className="flex-1 min-w-0">
                    <div className="flex items-baseline gap-2 text-sm">
                        <span className="text-xs text-(--theme-muted) font-mono whitespace-nowrap">
                            {new Date(event.eventTs).toLocaleTimeString()}
                        </span>
                        <span className="text-xs uppercase tracking-wider text-(--theme-muted)">
                            {event.eventType.replace(/_/g, ' ')}
                        </span>
                        {showAgentId && event.agentId && (
                            <span className="text-xs font-mono text-(--agent-blue) truncate" title={event.agentId}>
                                {event.agentId.slice(0, 16)}
                            </span>
                        )}
                        {showRunId && event.runId && (
                            <span className="text-xs font-mono text-(--theme-muted)/70 truncate" title={event.runId}>
                                run={event.runId.slice(0, 12)}
                            </span>
                        )}
                    </div>
                    <div className="text-sm mt-0.5">{renderSummary(event)}</div>
                </div>
                {hasPayload && (
                    <Chevron className="w-3.5 h-3.5 text-(--theme-muted) mt-1 shrink-0" />
                )}
            </div>
            {expanded && hasPayload && (
                <div className="px-4 pb-3 pl-12">
                    <pre className="text-xs font-mono bg-(--theme-card) border border-(--theme-muted)/10 rounded-md p-3 overflow-x-auto">
                        {JSON.stringify(event.payload, null, 2)}
                    </pre>
                </div>
            )}
        </div>
    );
};

export interface EventTimelineProps {
    /** Builds the SSE path (relative to /api) for a given resume cursor. */
    buildStreamPath: (sinceId: number) => string;
    /**
     * Close the stream once a terminal RUN_* event is received. True for a single run's timeline;
     * false for the long-lived per-agent stream, which spans many runs and must not close on any
     * one run's RUN_COMPLETE/RUN_FAILED.
     */
    closeOnTerminalEvent: boolean;
    /**
     * Cursor to open the stream at. Defaults to 0 (full replay). Pass a negative value to start at
     * the live tail — the backend resolves it to the current max event id and streams only new
     * events, avoiding a full replay of a large history. The component resumes reconnects from the
     * last real (non-negative) id it received.
     */
    initialSinceId?: number;
    /** Copy shown when no events have been received yet. */
    emptyMessage: string;
    /**
     * Optional secondary signal: poll an external source and close the stream when it reports the
     * underlying resource reached a terminal state. Used by the run view as a fallback when the SSE
     * terminal event is missed. Omit for long-lived streams.
     */
    terminalPoll?: { intervalMs: number; isTerminal: () => Promise<boolean> };
    /** Header label. Defaults to "Event timeline". */
    title?: string;
    /** Show the originating run id on each row (useful when events span multiple runs). */
    showRunId?: boolean;
    /** Show the originating agent id on each row (useful when events span multiple agents). */
    showAgentId?: boolean;
}

/**
 * Streams agent_run_events over SSE and renders them as a live, expandable timeline. Shared by the
 * per-run view (RunEventTimeline), the per-agent view (AgentEventTimeline), and the org-wide view
 * (OrgEventTimeline); they differ only in the stream URL, whether a terminal RUN_* event closes the
 * connection, and which id columns are shown per row.
 *
 * <p>Callers must give this a React {@code key} tied to the target (run id / agent id) so switching
 * targets remounts the component with fresh state, rather than appending to the prior timeline.
 */
export const EventTimeline: React.FC<EventTimelineProps> = ({
    buildStreamPath,
    closeOnTerminalEvent,
    initialSinceId = 0,
    emptyMessage,
    terminalPoll,
    title = 'Event timeline',
    showRunId = false,
    showAgentId = false,
}) => {
    const [events, setEvents] = useState<RunEvent[]>([]);
    const [connecting, setConnecting] = useState(true);
    const [connectionExhausted, setConnectionExhausted] = useState(false);
    const [reconnectAttempt, setReconnectAttempt] = useState(0);
    const [streamClosed, setStreamClosed] = useState(false);
    const lastReceivedIdRef = useRef<number>(initialSinceId);
    const seenIdsRef = useRef<Set<number>>(new Set());

    // Latest closures captured in refs so the subscribe effect depends only on resetKey /
    // closeOnTerminalEvent — not on caller-provided functions that change identity each render.
    // Synced in an effect (not during render) so reconnects/polls — which fire after render via
    // timers — always read the current closures.
    const buildStreamPathRef = useRef(buildStreamPath);
    const terminalPollRef = useRef(terminalPoll);
    useEffect(() => {
        buildStreamPathRef.current = buildStreamPath;
        terminalPollRef.current = terminalPoll;
    });

    useEffect(() => {
        let cancelled = false;
        let abortController: AbortController | null = null;
        let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
        let pollTimer: ReturnType<typeof setInterval> | null = null;
        let attempts = 0;

        const closeStream = () => {
            abortController?.abort();
            abortController = null;
        };

        const startPollingFallback = () => {
            const poll = terminalPollRef.current;
            if (!poll || pollTimer) return;
            pollTimer = setInterval(async () => {
                try {
                    if (await poll.isTerminal()) {
                        if (cancelled) return;
                        closeStream();
                        setStreamClosed(true);
                        if (pollTimer) {
                            clearInterval(pollTimer);
                            pollTimer = null;
                        }
                    }
                } catch {
                    // ignore poll errors; SSE remains the primary signal
                }
            }, poll.intervalMs);
        };

        const connect = () => {
            if (cancelled) return;
            setConnecting(true);

            // SSE — typed body N/A; see contract audit 2026-05-09. EventSource carries
            // discrete `event`/`data` strings, not a typed JSON body, so ApiClient.stream
            // intentionally does not take a `<T>` generic.
            abortController = ApiClient.stream(
                buildStreamPathRef.current(lastReceivedIdRef.current),
                {
                    onOpen: () => {
                        if (cancelled) return;
                        attempts = 0;
                        setReconnectAttempt(0);
                        setConnecting(false);
                    },
                    onMessage: (msg) => {
                        if (cancelled) return;
                        try {
                            const parsed = JSON.parse(msg.data) as RunEvent;
                            if (typeof parsed.id !== 'number') return;
                            if (seenIdsRef.current.has(parsed.id)) return;
                            seenIdsRef.current.add(parsed.id);
                            if (parsed.id > lastReceivedIdRef.current) {
                                lastReceivedIdRef.current = parsed.id;
                            }
                            setEvents((prev) => {
                                const next = [...prev, parsed];
                                next.sort((a, b) => a.id - b.id);
                                return next;
                            });
                            if (closeOnTerminalEvent && TERMINAL_RUN_EVENT_TYPES.has(parsed.eventType)) {
                                closeStream();
                                setStreamClosed(true);
                                if (pollTimer) {
                                    clearInterval(pollTimer);
                                    pollTimer = null;
                                }
                            }
                        } catch {
                            // Ignore malformed messages.
                        }
                    },
                    onError: (err) => {
                        if (cancelled) return;
                        attempts += 1;
                        setReconnectAttempt(attempts);
                        if (attempts >= MAX_RECONNECT_ATTEMPTS) {
                            setConnectionExhausted(true);
                            setConnecting(false);
                            // Throw to stop fetch-event-source's own retry loop.
                            throw err;
                        }
                        const delay = backoffMs(attempts);
                        reconnectTimer = setTimeout(() => {
                            if (!cancelled) connect();
                        }, delay);
                        // Throw to stop the library's automatic retry; we control it.
                        throw err;
                    },
                    onClose: () => {
                        if (cancelled) return;
                        setConnecting(false);
                    },
                },
            );
        };

        startPollingFallback();
        connect();

        return () => {
            cancelled = true;
            closeStream();
            if (reconnectTimer) clearTimeout(reconnectTimer);
            if (pollTimer) clearInterval(pollTimer);
        };
    }, [closeOnTerminalEvent]);

    const sortedEvents = useMemo(() => events, [events]);

    return (
        <div className="space-y-4">
            {connectionExhausted && (
                <Alert severity="error" title="Live event stream unavailable">
                    Failed to maintain a connection after {MAX_RECONNECT_ATTEMPTS} attempts. Refresh
                    the page to retry. Events shown below were received before the stream dropped.
                </Alert>
            )}
            {!connectionExhausted && reconnectAttempt > 0 && !streamClosed && (
                <Alert severity="warning" title={`Reconnecting (attempt ${reconnectAttempt}/${MAX_RECONNECT_ATTEMPTS})`}>
                    Temporarily disconnected from the event stream. Retrying…
                </Alert>
            )}

            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden">
                <div className="px-4 py-2 border-b border-(--theme-muted)/10 flex items-center justify-between">
                    <div className="text-xs font-medium text-(--theme-foreground)">
                        {title}
                        {sortedEvents.length > 0 && (
                            <span className="ml-2 text-(--theme-muted)">({sortedEvents.length})</span>
                        )}
                    </div>
                    {connecting && !streamClosed && !connectionExhausted ? (
                        <span className="inline-flex items-center gap-2 text-xs text-(--theme-muted)">
                            <span className="loading loading-spinner loading-xs" />
                            Connecting…
                        </span>
                    ) : streamClosed ? (
                        <span className="inline-flex items-center gap-2 text-xs text-(--theme-muted)">
                            <LuCircleCheck className="w-3.5 h-3.5" />
                            Stream closed
                        </span>
                    ) : !connectionExhausted ? (
                        <span className="inline-flex items-center gap-2 text-xs text-info">
                            <span className="w-2 h-2 rounded-full bg-info animate-pulse" />
                            Live
                        </span>
                    ) : (
                        <span className="inline-flex items-center gap-2 text-xs text-error">
                            <LuTriangleAlert className="w-3.5 h-3.5" />
                            Disconnected
                        </span>
                    )}
                </div>

                {sortedEvents.length === 0 && connecting ? (
                    <div className="p-4 space-y-2">
                        {[1, 2, 3].map(i => (
                            <div key={i} className="h-10 bg-obsidian-elevated/50 rounded animate-pulse" />
                        ))}
                    </div>
                ) : sortedEvents.length === 0 ? (
                    <div className="p-8 text-center text-(--theme-muted) text-sm">
                        <LuCircleAlert className="w-5 h-5 mx-auto mb-2" />
                        {emptyMessage}
                    </div>
                ) : (
                    <div>
                        {sortedEvents.map(e => (
                            <EventRow key={e.id} event={e} showRunId={showRunId} showAgentId={showAgentId} />
                        ))}
                    </div>
                )}
            </div>
        </div>
    );
};
