import React from 'react';
import { EventTimeline } from '../../runs/components/EventTimeline';

/**
 * Org-wide live event firehose: every agent_run_events row for the caller's tenant, across all
 * agents and runs, streamed in realtime. Streams GET /v1/observability/events (start-from-latest,
 * long-lived). Shows the agent and run id per row since events span many agents. A thin wrapper over
 * the shared {@link EventTimeline}.
 */
export const LiveEventsTab: React.FC = () => (
    <EventTimeline
        buildStreamPath={(sinceId) => `/v1/observability/events?sinceId=${sinceId}`}
        closeOnTerminalEvent={false}
        initialSinceId={-1}
        showAgentId
        showRunId
        title="Live events — all agents"
        emptyMessage="Listening for new events across all agents. Activity appears here in realtime as agents run."
    />
);
