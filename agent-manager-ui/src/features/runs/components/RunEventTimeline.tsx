import React from 'react';
import { EventTimeline } from './EventTimeline';
import { runsApi } from '../api/runsApi';
import { TERMINAL_RUN_STATUSES } from '../types/runs';

const TERMINAL_POLL_INTERVAL_MS = 15_000;

interface RunEventTimelineProps {
    runId: string;
    initialStatusIsTerminal: boolean;
}

/**
 * Single-run event timeline. Streams GET /v1/runs/{runId}/events and closes once a terminal RUN_*
 * event arrives; when the run is still active on mount, also polls the run status as a fallback in
 * case the SSE terminal event is missed. A thin wrapper over the shared {@link EventTimeline}.
 */
export const RunEventTimeline: React.FC<RunEventTimelineProps> = ({ runId, initialStatusIsTerminal }) => (
    <EventTimeline
        key={runId}
        buildStreamPath={(sinceId) => `/v1/runs/${encodeURIComponent(runId)}/events?sinceId=${sinceId}`}
        closeOnTerminalEvent
        terminalPoll={initialStatusIsTerminal ? undefined : {
            intervalMs: TERMINAL_POLL_INTERVAL_MS,
            isTerminal: async () => {
                const run = await runsApi.get(runId);
                return TERMINAL_RUN_STATUSES.has(run.status);
            },
        }}
        emptyMessage="No events recorded for this run yet."
    />
);
