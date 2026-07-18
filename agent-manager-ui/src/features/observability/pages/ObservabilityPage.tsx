import React, { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { LuActivity } from 'react-icons/lu';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { Tabs } from '../../../shared/components/ui/Tabs';
import { AlertEventHistoryPanel } from '../components/AlertEventHistoryPanel';
import { BackgroundJobMonitorTab } from '../components/BackgroundJobMonitorTab';
import { DelegationTopologyTab } from '../components/DelegationTopologyTab';
import { EvaluationMatrix } from '../components/EvaluationMatrix';
import { OrchestrationAnalyticsTab } from '../components/OrchestrationAnalyticsTab';
import { OtlpSettings } from '../components/OtlpSettings';
import { SafetyAnalyticsTab } from '../components/SafetyAnalyticsTab';
import { SessionAnalyticsTab } from '../components/SessionAnalyticsTab';
import { SloCompliancePanel } from '../components/SloCompliancePanel';
import { ToolUsageAnalyticsTab } from '../components/ToolUsageAnalyticsTab';
import { JvmDiagnosticsPanel } from '../components/JvmDiagnosticsPanel';
import { LiveEventsTab } from '../components/LiveEventsTab';

type GroupId = 'operations' | 'analytics';

interface TabDef {
    slug: string;
    label: string;
    content: React.ReactNode;
}

const OPERATIONS_TABS: TabDef[] = [
    { slug: 'system-health', label: 'System Health', content: <OtlpSettings /> },
    { slug: 'live-events', label: 'Live Events', content: <LiveEventsTab /> },
    { slug: 'slos', label: 'SLOs', content: <SloCompliancePanel /> },
    { slug: 'alerts-history', label: 'Alerts History', content: <AlertEventHistoryPanel /> },
    { slug: 'background-jobs', label: 'Background Jobs', content: <BackgroundJobMonitorTab /> },
    { slug: 'diagnostics', label: 'Diagnostics', content: <JvmDiagnosticsPanel /> },
];

const ANALYTICS_TABS: TabDef[] = [
    { slug: 'run-analytics', label: 'Run Analytics', content: <EvaluationMatrix /> },
    { slug: 'orchestration', label: 'Orchestration', content: <OrchestrationAnalyticsTab /> },
    { slug: 'tools', label: 'Tools', content: <ToolUsageAnalyticsTab /> },
    { slug: 'delegation-topology', label: 'Delegation Topology', content: <DelegationTopologyTab /> },
    { slug: 'safety', label: 'Safety', content: <SafetyAnalyticsTab /> },
    { slug: 'sessions', label: 'Sessions', content: <SessionAnalyticsTab /> },
];

const GROUPS: Record<GroupId, { label: string; tabs: TabDef[] }> = {
    operations: { label: 'Operations', tabs: OPERATIONS_TABS },
    analytics: { label: 'Analytics', tabs: ANALYTICS_TABS },
};

const isGroup = (v: string | null): v is GroupId => v === 'operations' || v === 'analytics';

export const ObservabilityPage: React.FC = () => {
    const [searchParams, setSearchParams] = useSearchParams();

    const group: GroupId = isGroup(searchParams.get('group')) ? (searchParams.get('group') as GroupId) : 'operations';
    const tabsForGroup = GROUPS[group].tabs;
    const requestedTab = searchParams.get('tab');
    const tab = useMemo(
        () => (tabsForGroup.find(t => t.slug === requestedTab)?.slug ?? tabsForGroup[0].slug),
        [tabsForGroup, requestedTab],
    );

    const handleGroupChange = (next: string) => {
        if (!isGroup(next)) return;
        const firstTab = GROUPS[next].tabs[0].slug;
        setSearchParams({ group: next, tab: firstTab }, { replace: false });
    };

    const handleTabChange = (next: string) => {
        setSearchParams({ group, tab: next }, { replace: false });
    };

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuActivity}
                title="Agent Observability"
                subtitle="System operations and cross-run analytics."
            />

            <Tabs value={group} defaultValue="operations" onValueChange={handleGroupChange}>
                <Tabs.List>
                    {(Object.keys(GROUPS) as GroupId[]).map(g => (
                        <Tabs.Trigger key={g} value={g}>{GROUPS[g].label}</Tabs.Trigger>
                    ))}
                </Tabs.List>

                {(Object.keys(GROUPS) as GroupId[]).map(g => (
                    <Tabs.Content key={g} value={g}>
                        <Tabs value={tab} defaultValue={GROUPS[g].tabs[0].slug} onValueChange={handleTabChange}>
                            <Tabs.List>
                                {GROUPS[g].tabs.map(t => (
                                    <Tabs.Trigger key={t.slug} value={t.slug}>{t.label}</Tabs.Trigger>
                                ))}
                            </Tabs.List>
                            {GROUPS[g].tabs.map(t => (
                                <Tabs.Content key={t.slug} value={t.slug}>
                                    {t.content}
                                </Tabs.Content>
                            ))}
                        </Tabs>
                    </Tabs.Content>
                ))}
            </Tabs>
        </PageContainer>
    );
};
