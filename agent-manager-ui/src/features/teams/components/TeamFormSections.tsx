import React from 'react';
import type { Team, MemberSlot, NodeRole, TeamManifest } from '../../../shared/types/orchestration';
import { Typography } from '../../../shared/components/ui/Typography';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { SearchableSelect } from '../../../shared/components/ui/SearchableSelect';
import { LuTrash2, LuPlus } from 'react-icons/lu';
import { TeamMemorySettings } from './TeamMemorySettings';
import { TEAM_MODE_LABELS, TEAM_MODE_DESCRIPTIONS } from '../constants/teamConstants';

/**
 * Presentational form sections extracted from TeamDetailsPage. Each is controlled
 * — all team/manifest/member state stays in the page and is passed down — so the
 * moved JSX is byte-identical and behavior is preserved. Prop names mirror the
 * page's local variables for that reason.
 *
 * The Transition-DAG section is intentionally left in the page for now (its edge
 * rows aren't exercised by the seeded-team visual baseline).
 */

type AgentOption = { value: string; label: string; sublabel?: string; disabled?: boolean; disabledReason?: string };

interface GeneralSettingsSectionProps {
    team: Partial<Team>;
    setTeamField: (updates: Partial<Team>) => void;
    agentOptions: AgentOption[];
}

export const GeneralSettingsSection: React.FC<GeneralSettingsSectionProps> = ({ team, setTeamField, agentOptions }) => (
    <section className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6 space-y-6">
        <Typography.Heading level={3} className="text-(--theme-foreground) font-semibold">General Settings</Typography.Heading>

        <div className="form-control">
            <label className="mb-2 block">
                <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider block">Team Name</span>
            </label>
            <input
                type="text"
                className="input input-bordered w-full font-medium"
                value={team.name || ''}
                onChange={e => setTeamField({ name: e.target.value })}
                required
                placeholder="Enter a unique identifier..."
            />
        </div>

        <div className="form-control">
            <label className="mb-2 block">
                <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider block">Description</span>
            </label>
            <textarea
                className="textarea textarea-bordered h-24 w-full text-sm leading-relaxed"
                value={team.description || ''}
                onChange={e => setTeamField({ description: e.target.value })}
                placeholder="This team manages..."
            />
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="form-control">
                <label className="mb-2 block">
                    <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider block">Team Mode</span>
                </label>
                <select
                    className="select select-bordered w-full"
                    value={team.teamMode || 'ROUTER'}
                    onChange={e => setTeamField({ teamMode: e.target.value })}
                >
                    {Object.entries(TEAM_MODE_LABELS).map(([key, label]) => (
                        <option key={key} value={key}>{label} — {TEAM_MODE_DESCRIPTIONS[key]}</option>
                    ))}
                </select>
            </div>
            <SearchableSelect
                label="Leader Agent"
                description="Optional. Required for COORDINATOR mode."
                value={team.leaderId || ''}
                onChange={v => setTeamField({ leaderId: v || undefined })}
                options={agentOptions}
                placeholder="Select leader agent..."
            />
        </div>
    </section>
);

interface MetaAgentSectionProps {
    team: Partial<Team>;
    setTeamField: (updates: Partial<Team>) => void;
    modelOptions: AgentOption[];
}

export const MetaAgentSection: React.FC<MetaAgentSectionProps> = ({ team, setTeamField, modelOptions }) => (
    <section className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6 space-y-6">
        <Typography.Heading level={3} className="text-(--theme-foreground) font-semibold">Meta-Agent Configuration</Typography.Heading>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <SearchableSelect
                label="Routing Model"
                description="The LLM utilized by the Team boundary for request routing."
                value={team.modelId || ''}
                onChange={v => setTeamField({ modelId: v || undefined })}
                options={modelOptions}
                placeholder="Select model..."
            />
            <div className="form-control">
                <label className="mb-2 block">
                    <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider block">Context Window Size</span>
                    <span className="text-xs text-(--theme-muted) font-normal mt-1 block">Token eviction threshold for shared memory.</span>
                </label>
                <input
                    type="number"
                    className="input input-bordered w-full font-mono text-sm"
                    value={team.contextWindowSize || ''}
                    onChange={e => setTeamField({ contextWindowSize: parseInt(e.target.value) || 0 })}
                    placeholder="4096"
                />
            </div>
        </div>

        <div className="form-control">
            <label className="mb-2 block">
                <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider block">Overarching Instructions (System Prompt)</span>
            </label>
            <textarea
                className="textarea textarea-bordered h-32 font-mono text-sm leading-relaxed w-full"
                value={team.instructions || ''}
                onChange={e => setTeamField({ instructions: e.target.value })}
                placeholder="You are the lead orchestrator. Analyze user input and select..."
            />
        </div>

        <div className="form-control">
            <label className="mb-2 block">
                <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider block">Shared Tools (Comma Separated)</span>
            </label>
            <input
                type="text"
                className="input input-bordered w-full font-mono text-sm"
                value={team.tools?.join(', ') || ''}
                onChange={e => setTeamField({ tools: e.target.value.split(',').map(s => s.trim()).filter(Boolean) })}
                placeholder="e.g. Memory_Writer, Search_Network"
            />
        </div>

        <TeamMemorySettings team={team} onChange={setTeamField} />
    </section>
);

interface MembersRosterSectionProps {
    members: MemberSlot[];
    availableAgents: { id: string; name: string; active?: boolean }[];
    agentOptions: AgentOption[];
    team: Partial<Team>;
    newAgentId: string;
    setNewAgentId: (v: string) => void;
    newAgentRole: NodeRole;
    setNewAgentRole: (v: NodeRole) => void;
    handleSlotAgentChange: (index: number, agentId: string) => void;
    handleAddMember: () => void;
    handleRemoveMember: (agentId: string) => void;
}

export const MembersRosterSection: React.FC<MembersRosterSectionProps> = ({
    members, availableAgents, agentOptions, team,
    newAgentId, setNewAgentId, newAgentRole, setNewAgentRole,
    handleSlotAgentChange, handleAddMember, handleRemoveMember,
}) => {
    const templateSlots = members.filter(m => m.isTemplateSlot);
    const manualMembers = members.filter(m => !m.isTemplateSlot);

    return (
        <section className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6 space-y-6">
            <Typography.Heading level={3} className="text-(--theme-foreground) font-semibold">Members Roster</Typography.Heading>

            {/* Template Slot Dropdowns */}
            {templateSlots.length > 0 && (
                <div className="space-y-4">
                    <div className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider">Template Slots — Assign an agent to each role</div>
                    {templateSlots.map((slot, idx) => {
                        const slotIndex = members.indexOf(slot);
                        return (
                            <div key={`slot-${idx}`} className="flex flex-col sm:flex-row sm:items-center gap-3 p-4 bg-obsidian-elevated rounded-lg border border-(--theme-muted)/10">
                                <div className="flex-1 min-w-0">
                                    <div className="flex items-center gap-2">
                                        <span className="font-semibold text-sm text-(--theme-foreground)">{slot.slotLabel}</span>
                                        {slot.role === 'LEADER' && <Badge variant="primary" className="text-[10px] uppercase font-bold tracking-wider">Leader</Badge>}
                                    </div>
                                    {slot.slotDescription && (
                                        <div className="text-xs text-(--theme-muted) mt-0.5">{slot.slotDescription}</div>
                                    )}
                                </div>
                                <div className="sm:w-64">
                                    <SearchableSelect
                                        value={slot.agentId || ''}
                                        onChange={v => handleSlotAgentChange(slotIndex, v)}
                                        options={agentOptions.map(o => ({
                                            ...o,
                                            disabled: o.disabled || members.some((m, i) => i !== slotIndex && m.agentId === o.value),
                                        }))}
                                        placeholder="Select agent..."
                                    />
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}

            {/* Manually added members */}
            {templateSlots.length > 0 && manualMembers.length > 0 && (
                <div className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider">Additional Members</div>
            )}
            {manualMembers.length === 0 && templateSlots.length === 0 && (
                <div className="text-center p-8 bg-(--theme-background) rounded-lg border-2 border-dashed border-(--theme-muted)/20">
                    <span className="text-(--theme-muted) text-sm">No members added yet.</span>
                </div>
            )}
            {manualMembers.map(member => {
                const agentName = availableAgents.find(a => a.id === member.agentId)?.name;
                return (
                    <div key={member.agentId} className="flex items-center justify-between p-4 bg-obsidian-elevated rounded-lg border border-(--theme-muted)/10">
                        <div>
                            <div className="flex items-center gap-3">
                                <span className="font-medium text-sm text-(--theme-foreground)">{agentName || member.agentId}</span>
                                {agentName && <span className="font-mono text-[10px] text-(--theme-muted)">{member.agentId}</span>}
                                {member.agentId === team.leaderId && <Badge variant="primary" className="text-[10px] uppercase font-bold tracking-wider">Leader</Badge>}
                            </div>
                            <div className="text-xs text-(--theme-muted) mt-1 uppercase tracking-wider">{member.role}</div>
                        </div>
                        <Button variant="ghost" size="sm" className="text-error hover:bg-error/10" onClick={() => handleRemoveMember(member.agentId)}>
                            <LuTrash2 className="w-3.5 h-3.5" />
                        </Button>
                    </div>
                );
            })}

            {/* Add New Member */}
            <div className="bg-obsidian-elevated p-5 rounded-lg border border-(--theme-muted)/10 space-y-4">
                <div className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider">Add New Member</div>
                <div className="flex flex-col sm:flex-row gap-4 items-end">
                    <div className="flex-1 w-full">
                        <SearchableSelect
                            value={newAgentId}
                            onChange={setNewAgentId}
                            options={agentOptions.filter(o => !members.some(m => m.agentId === o.value))}
                            placeholder="Select agent..."
                        />
                    </div>
                    <div className="w-full sm:w-48">
                        <select
                            className="select select-bordered w-full"
                            value={newAgentRole}
                            onChange={e => setNewAgentRole(e.target.value as NodeRole)}
                        >
                            <option value="MEMBER">Member</option>
                            <option value="LEADER">Leader</option>
                        </select>
                    </div>
                    <Button type="button" size="sm" className="gap-1.5 w-full sm:w-auto" onClick={handleAddMember} disabled={!newAgentId.trim()}>
                        <LuPlus className="w-4 h-4" /> Add
                    </Button>
                </div>
            </div>
        </section>
    );
};

interface FinOpsSectionProps {
    manifest: Partial<TeamManifest>;
    setManifest: (m: Partial<TeamManifest>) => void;
    setDirty: (v: boolean) => void;
}

export const FinOpsSection: React.FC<FinOpsSectionProps> = ({ manifest, setManifest, setDirty }) => (
    <section className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6 space-y-6">
        <Typography.Heading level={3} className="text-(--theme-foreground) font-semibold">FinOps & Budget Constraints</Typography.Heading>
        <p className="text-xs text-(--theme-muted)">Establish hard boundaries for API expenditure and human oversight protocol.</p>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="form-control">
                <label className="mb-2 block">
                    <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider block">Human Lead ID</span>
                    <span className="text-xs text-(--theme-muted) font-normal mt-1 block">Username for manual HITL override validation.</span>
                </label>
                <input
                    type="text"
                    className="input input-bordered font-mono text-sm"
                    value={manifest.humanLead || ''}
                    onChange={e => { setManifest({...manifest, humanLead: e.target.value}); setDirty(true); }}
                    placeholder="e.g. sys_admin"
                />
            </div>
            <div></div>

            <div className="form-control">
                <label className="mb-2 block">
                    <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider block">Max Daily Spend ($)</span>
                    <span className="text-xs text-(--theme-muted) font-normal mt-1 block">Hard eviction if inference exceeds this threshold.</span>
                </label>
                <input
                    type="number"
                    className="input input-bordered font-mono text-sm"
                    value={manifest.maxDailySpend || 0}
                    onChange={e => { setManifest({...manifest, maxDailySpend: parseFloat(e.target.value) || 0}); setDirty(true); }}
                    step="0.01"
                />
            </div>

            <div className="form-control">
                <label className="mb-2 block">
                    <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider block">Min Spending Authority ($)</span>
                    <span className="text-xs text-(--theme-muted) font-normal mt-1 block">Threshold for Tier 3 escalation trigger.</span>
                </label>
                <input
                    type="number"
                    className="input input-bordered font-mono text-sm"
                    value={manifest.minSpendingAuthority || 0}
                    onChange={e => { setManifest({...manifest, minSpendingAuthority: parseFloat(e.target.value) || 0}); setDirty(true); }}
                    step="0.01"
                />
            </div>
        </div>
    </section>
);
