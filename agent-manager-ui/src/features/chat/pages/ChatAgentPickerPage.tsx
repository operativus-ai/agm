import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AgentsApi } from '../../agents/api/agents-api';
import type { AgentConfig } from '../../../shared/types/api';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { TemplatePickerGrid } from '../../../shared/components/ui/TemplatePickerGrid';
import type { TemplateCardItem } from '../../../shared/components/ui/TemplatePickerGrid';
import { Alert } from '../../../shared/components/ui/Alert';
import { LuMessageSquare, LuSearch } from 'react-icons/lu';

// Single-agent rooms render as a chat bubble; team rooms get a coordinator
// glyph so the operator can distinguish them at a glance.
const AGENT_ICON = '\u{1F4AC}'; // 💬
const TEAM_ICON = '\u{1F465}';  // 👥

export const ChatAgentPickerPage: React.FC = () => {
    const navigate = useNavigate();
    const [agents, setAgents] = useState<AgentConfig[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [search, setSearch] = useState('');

    useEffect(() => {
        AgentsApi.getAgents()
            .then(data => setAgents(Array.isArray(data) ? data : []))
            .catch(err => setError(err?.message || 'Failed to load agents.'))
            .finally(() => setLoading(false));
    }, []);

    const filteredAgents = useMemo(() => {
        if (!search.trim()) return agents;
        const q = search.toLowerCase();
        return agents.filter(a =>
            a.name?.toLowerCase().includes(q)
            || a.description?.toLowerCase().includes(q)
            || a.model?.toLowerCase().includes(q),
        );
    }, [agents, search]);

    const items: TemplateCardItem[] = filteredAgents.map(a => ({
        id: a.agentId,
        icon: a.isTeam ? TEAM_ICON : AGENT_ICON,
        name: a.name,
        description: a.description || (a.isTeam
            ? 'No description provided. Multi-agent team — leadership delegates to members.'
            : 'No description provided.'),
        badge: a.isTeam ? 'Team' : undefined,
        metadata: a.model ? `Model: ${a.model}` : undefined,
    }));

    const handleSelect = (agentId: string) => {
        navigate(`/chat/${agentId}`);
    };

    return (
        <PageContainer>
            <PageHeader
                icon={LuMessageSquare}
                title="Choose an Agent to Chat With"
                subtitle="Pick a single-agent room or a multi-agent team. New sessions auto-generate; existing sessions are listed in the chat sidebar."
            />

            {error && (
                <Alert severity="error" title="Failed to load agents" description={error} />
            )}

            <div className="relative max-w-md">
                <LuSearch className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-(--theme-muted)" />
                <input
                    type="text"
                    placeholder="Search agents and teams..."
                    className="input input-bordered input-sm w-full pl-9"
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                />
            </div>

            {!loading && !error && filteredAgents.length === 0 && (
                <div className="text-center p-12 text-(--theme-muted) bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl">
                    {agents.length === 0
                        ? 'No active agents registered. Create one from the Agents page to start chatting.'
                        : `No agents match "${search}".`
                    }
                </div>
            )}

            <TemplatePickerGrid
                items={items}
                onSelect={handleSelect}
                loading={loading}
            />
        </PageContainer>
    );
};
