import React from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LuActivity, LuArrowLeft, LuBot } from 'react-icons/lu';

import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Tabs } from '../../../shared/components/ui/Tabs';
import { Badge } from '../../../shared/components/ui/Badge';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import type { AgentConfig } from '../../../shared/types/api';

import { AgentsApi } from '../api/agents-api';
import { AgentEventTimeline } from '../components/AgentEventTimeline';
import { AgentOverviewTab } from '../components/AgentOverviewTab';
import { AgentReflectionsTab } from '../components/AgentReflectionsTab';
import { AgentRunsTab } from '../components/AgentRunsTab';
import { AgentSafetyTab } from '../components/AgentSafetyTab';

const TAB_DEFS: ReadonlyArray<{
    slug: string;
    label: string;
    render: (agent: AgentConfig) => React.ReactNode;
}> = [
    { slug: 'overview', label: 'Overview', render: (a) => <AgentOverviewTab agent={a} /> },
    { slug: 'runs', label: 'Runs', render: (a) => <AgentRunsTab agentId={a.agentId} /> },
    { slug: 'events', label: 'Events', render: (a) => <AgentEventTimeline agentId={a.agentId} /> },
    { slug: 'reflections', label: 'Reflections', render: (a) => <AgentReflectionsTab agentId={a.agentId} /> },
    { slug: 'safety', label: 'Safety', render: (a) => <AgentSafetyTab agentId={a.agentId} /> },
];

const TabStub: React.FC<{ label: string }> = ({ label }) => (
    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
        <div className="font-medium text-(--theme-foreground)">{label}</div>
        <div className="mt-2 text-sm">Loading…</div>
    </div>
);

export const AgentDetailsPage: React.FC = () => {
    const { agentId } = useParams<{ agentId: string }>();

    const { data: agent, isLoading, error } = useQuery({
        queryKey: ['agents', 'detail', agentId],
        queryFn: () => AgentsApi.getAgent(agentId!),
        enabled: Boolean(agentId),
        staleTime: 30_000,
    });

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuBot}
                title={agentId ? (agent?.name ?? agentId) : 'Agent'}
                actions={
                    <div className="flex items-center gap-2">
                        {agentId && (
                            <Link to={`/agents/${agentId}/events`}>
                                <Button variant="ghost" size="sm" className="gap-2">
                                    <LuActivity className="w-4 h-4" />
                                    Live events
                                </Button>
                            </Link>
                        )}
                        <Link to="/agents">
                            <Button variant="ghost" size="sm" className="gap-2">
                                <LuArrowLeft className="w-4 h-4" />
                                Back to agents
                            </Button>
                        </Link>
                    </div>
                }
            />

            <div className="flex items-center gap-3 text-xs text-(--theme-muted) -mt-2 flex-wrap">
                {agent ? (
                    <>
                        <Badge variant={agent.active === false ? 'error' : 'success'} className="text-xs">
                            {agent.active === false ? 'INACTIVE' : 'ACTIVE'}
                        </Badge>
                        <span className="font-mono">id={agent.agentId}</span>
                        {agent.model && <span className="font-mono">model={agent.model}</span>}
                        {agent.isTeam && <Badge variant="info" className="text-xs">team</Badge>}
                        {agent.maintenanceMode && <Badge variant="warning" className="text-xs">maintenance</Badge>}
                    </>
                ) : isLoading ? (
                    <span>Loading agent details…</span>
                ) : (
                    <span>Agent details unavailable</span>
                )}
            </div>

            {error && (
                <Alert
                    severity="error"
                    title={(error as { status?: number }).status === 404 ? 'Agent not found' : 'Failed to load agent'}
                >
                    {(error as Error).message}
                </Alert>
            )}

            <Tabs defaultValue="overview">
                <Tabs.List>
                    {TAB_DEFS.map(t => (
                        <Tabs.Trigger key={t.slug} value={t.slug}>{t.label}</Tabs.Trigger>
                    ))}
                </Tabs.List>
                {TAB_DEFS.map(t => (
                    <Tabs.Content key={t.slug} value={t.slug}>
                        {agent ? t.render(agent) : <TabStub label={t.label} />}
                    </Tabs.Content>
                ))}
            </Tabs>
        </PageContainer>
    );
};
