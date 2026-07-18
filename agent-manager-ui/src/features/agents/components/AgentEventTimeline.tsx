import React from 'react';
import { EventTimeline } from '../../runs/components/EventTimeline';

interface AgentEventTimelineProps {
    agentId: string;
}

/**
 * Long-lived per-agent event timeline. Streams GET /v1/agents/{agentId}/events — every
 * agent_run_events row for this agent across all of its runs — and stays open across runs (it does
 * NOT close on any single run's terminal event). Starts at the live tail (initialSinceId=-1) rather
 * than replaying the agent's full history, which can be large. A thin wrapper over the shared
 * {@link EventTimeline}.
 */
export const AgentEventTimeline: React.FC<AgentEventTimelineProps> = ({ agentId }) => (
    <EventTimeline
        key={agentId}
        buildStreamPath={(sinceId) => `/v1/agents/${encodeURIComponent(agentId)}/events?sinceId=${sinceId}`}
        closeOnTerminalEvent={false}
        initialSinceId={-1}
        showRunId
        title="Agent events"
        emptyMessage="Listening for new events. Activity appears here in realtime as the agent runs."
    />
);
