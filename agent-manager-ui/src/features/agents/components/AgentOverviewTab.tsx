import React from 'react';
import type { AgentConfig } from '../../../shared/types/api';

interface AgentOverviewTabProps {
    agent: AgentConfig;
}

export const AgentOverviewTab: React.FC<AgentOverviewTabProps> = ({ agent }) => (
    <div className="space-y-4">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <Card title="Identity">
                <Field label="Agent ID" value={agent.agentId} mono />
                <Field label="Name" value={agent.name} />
                {agent.description && <Field label="Description" value={agent.description} />}
                <Field label="Model" value={agent.model} mono />
                {agent.contextWindowSize != null && (
                    <Field label="Context window" value={agent.contextWindowSize.toLocaleString()} />
                )}
            </Card>

            <Card title="Configuration">
                <Field label="Memory" value={boolStr(agent.memoryEnabled)} />
                <Field label="History → messages" value={boolStr(agent.addHistoryToMessages)} />
                <Field label="Reasoning" value={boolStr(agent.isReasoningEnabled)} />
                <Field label="Enforce JSON output" value={boolStr(agent.enforceJsonOutput)} />
                <Field label="PII redaction" value={boolStr(agent.requiresPiiRedaction)} />
                {agent.tools && agent.tools.length > 0 && (
                    <Field label="Tools" value={agent.tools.join(', ')} mono />
                )}
            </Card>

            {agent.isTeam && (
                <Card title="Team">
                    {agent.teamMode && <Field label="Mode" value={agent.teamMode} mono />}
                    {agent.members && agent.members.length > 0 && (
                        <Field label="Members" value={agent.members.join(', ')} mono />
                    )}
                </Card>
            )}

            <Card title="Lifecycle">
                <Field label="Active" value={boolStr(agent.active !== false)} />
                <Field label="Approved for production" value={boolStr(agent.approvedForProduction)} />
                <Field label="Maintenance mode" value={boolStr(agent.maintenanceMode)} />
                {agent.primaryOwner && <Field label="Primary owner" value={agent.primaryOwner} />}
                {agent.supportChannel && <Field label="Support channel" value={agent.supportChannel} mono />}
            </Card>
        </div>

        {agent.instructions && (
            <Card title="Instructions">
                <pre className="whitespace-pre-wrap text-xs text-(--theme-foreground) leading-relaxed">
                    {agent.instructions}
                </pre>
            </Card>
        )}
    </div>
);

const Card: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-5 py-4">
        <div className="text-xs font-medium text-(--theme-foreground) mb-3 uppercase tracking-wider">{title}</div>
        <div className="space-y-2">{children}</div>
    </div>
);

const Field: React.FC<{ label: string; value: string; mono?: boolean }> = ({ label, value, mono }) => (
    <div className="flex items-baseline justify-between gap-3">
        <span className="text-[11px] uppercase tracking-wider text-(--theme-muted) shrink-0">{label}</span>
        <span className={`text-xs text-(--theme-foreground) text-right ${mono ? 'font-mono' : ''} truncate`} title={value}>
            {value}
        </span>
    </div>
);

const boolStr = (v: boolean | undefined): string => (v ? 'Yes' : 'No');
