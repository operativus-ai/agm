import React from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LuActivity, LuArrowLeft } from 'react-icons/lu';

import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';

import { AgentsApi } from '../api/agents-api';
import { AgentEventTimeline } from '../components/AgentEventTimeline';

/**
 * Full-page, deep-linkable live view of a single agent's execution events. Same realtime stream as
 * the agent detail "Events" tab, but standalone — useful for bookmarking or watching one agent in
 * its own browser tab/monitor. Streams GET /v1/agents/{agentId}/events (start-from-latest).
 */
export const AgentEventsPage: React.FC = () => {
    const { agentId } = useParams<{ agentId: string }>();

    const { data: agent } = useQuery({
        queryKey: ['agents', 'detail', agentId],
        queryFn: () => AgentsApi.getAgent(agentId!),
        enabled: Boolean(agentId),
        staleTime: 30_000,
    });

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuActivity}
                title={`Events — ${agent?.name ?? agentId ?? 'Agent'}`}
                actions={
                    <Link to={agentId ? `/agents/${agentId}` : '/agents'}>
                        <Button variant="ghost" size="sm" className="gap-2">
                            <LuArrowLeft className="w-4 h-4" />
                            Back to agent
                        </Button>
                    </Link>
                }
            />

            {agentId && <AgentEventTimeline agentId={agentId} />}
        </PageContainer>
    );
};
