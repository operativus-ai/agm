import React, { useEffect, useRef, useState } from 'react';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import type { Team, TeamMember, NodeRole, TeamManifest, TeamTemplate, MemberSlot, TeamHealth } from '../../../shared/types/orchestration';
import type { TransitionConstraint } from '../../../shared/types/api';
import { Typography } from '../../../shared/components/ui/Typography';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { TemplatePickerGrid } from '../../../shared/components/ui/TemplatePickerGrid';
import type { TemplateCardItem } from '../../../shared/components/ui/TemplatePickerGrid';
import { Button } from '../../../shared/components/ui/Button';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { SearchableSelect } from '../../../shared/components/ui/SearchableSelect';
import { useParams, useNavigate, useBlocker } from 'react-router-dom';
import { LuTrash2, LuPlus, LuArrowLeft, LuSave, LuUsers, LuLayoutGrid } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { AgentsApi } from '../../agents/api/agents-api';
import { ModelsApi } from '../../models/api/models-api';
import type { ModelConfig } from '../../models/types/models.types';
import { TeamHealthBar } from '../components/TeamHealthBar';
import { GeneralSettingsSection, MetaAgentSection, MembersRosterSection, FinOpsSection } from '../components/TeamFormSections';
import { TEMPLATE_ICONS, TEAM_MODE_LABELS } from '../constants/teamConstants';

export const TeamDetailsPage: React.FC = () => {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const isNew = id === 'new';

    // Wizard step: template picker → form (edit mode skips picker)
    const [wizardStep, setWizardStep] = useState<'template' | 'form'>(isNew ? 'template' : 'form');
    const [templates, setTemplates] = useState<TeamTemplate[]>([]);
    const [templateError, setTemplateError] = useState<string | null>(null);

    const [team, setTeam] = useState<Partial<Team> | null>(null);
    const [members, setMembers] = useState<MemberSlot[]>([]);
    const [originalMembers, setOriginalMembers] = useState<TeamMember[]>([]);
    const [newAgentId, setNewAgentId] = useState('');
    const [newAgentRole, setNewAgentRole] = useState<NodeRole>('MEMBER');
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);

    // Transition Edge state — local state for both create and edit
    const [transitionEdges, setTransitionEdges] = useState<TransitionConstraint[]>([]);
    const [localEdges, setLocalEdges] = useState<{ sourceAgentId: string; targetAgentId: string }[]>([]);
    const [newEdgeSource, setNewEdgeSource] = useState('');
    const [newEdgeTarget, setNewEdgeTarget] = useState('');

    // FinOps & Manifest state
    const [manifest, setManifest] = useState<Partial<TeamManifest>>({});

    // Autocomplete agent state
    const [availableAgents, setAvailableAgents] = useState<{id: string, name: string, active?: boolean}[]>([]);

    // Model selector state
    const [models, setModels] = useState<ModelConfig[]>([]);

    // Team health snapshot (existing teams only)
    const [health, setHealth] = useState<TeamHealth | null>(null);

    // Dirty state tracking + navigation guard
    const [dirty, setDirty] = useState(false);
    const [leaveOpen, setLeaveOpen] = useState(false);
    const pendingNavigate = useRef<(() => void) | null>(null);
    const blocker = useBlocker(dirty);

    useEffect(() => {
        if (blocker.state === 'blocked') {
            setLeaveOpen(true);
            pendingNavigate.current = () => blocker.proceed();
        }
    }, [blocker.state]);

    useEffect(() => {
        AgentsApi.getAgents()
            .then(data => setAvailableAgents(data.map(a => ({ id: a.agentId, name: a.name, active: a.active !== false }))))
            .catch(err => console.error("Failed to load agents for picker", err));

        ModelsApi.getModels({ size: 100 })
            .then(data => setModels(data.content || []))
            .catch(err => console.error("Failed to load models", err));

        if (isNew) {
            orchestrationApi.getTeamTemplates()
                .then(data => setTemplates(data))
                .catch(err => {
                    console.error("Failed to load team templates", err);
                    setTemplateError("Failed to load templates. You can still create a team manually.");
                    setWizardStep('form');
                });
            setTeam({ name: '', description: '', members: [] });
            setLoading(false);
        } else if (id) {
            loadTeam(id);
        }
    }, [id]);

    const loadTeam = async (teamId: string) => {
        try {
            setLoading(true);
            const [data, edges, manifests, healthData] = await Promise.all([
                orchestrationApi.getTeam(teamId),
                orchestrationApi.getTransitionEdges(teamId).catch(() => []),
                orchestrationApi.listTeamManifests().catch(() => []),
                orchestrationApi.getTeamHealth(teamId).catch(() => null),
            ]);
            setTeam(data);
            setMembers(data.members || []);
            setOriginalMembers(data.members || []);
            setTransitionEdges(edges);
            setHealth(healthData);

            const teamMan = manifests.find(m => m.teamId === teamId);
            if (teamMan) {
                setManifest(teamMan);
            } else {
                setManifest({ teamId, maxDailySpend: 100, minSpendingAuthority: 10 });
            }
        } catch (err) {
            console.error("API Fetch failed", err);
            setError('Failed to load team.');
        } finally {
            setLoading(false);
        }
    };

    // ── Helper: build agent picker options ──────────────────────
    const agentOptions = availableAgents.map(a => ({
        value: a.id,
        label: a.name,
        sublabel: a.id,
        disabled: a.active === false,
        disabledReason: a.active === false ? 'Agent is inactive' : undefined,
    }));

    const modelOptions = models
        .filter(m => m.modelType === 'CHAT' || !m.modelType)
        .map(m => ({
            value: m.id,
            label: m.name,
            sublabel: m.modelName || m.id,
        }));

    // ── Template Selection ──────────────────────────────────────
    const handleSelectTemplate = (template: TeamTemplate) => {
        if (template.id === 'custom') {
            setTeam({ name: '', description: '', members: [] });
            setMembers([]);
        } else {
            setTeam({
                name: '',
                description: '',
                teamMode: template.teamMode || undefined,
                instructions: template.instructions || undefined,
                memoryEnabled: template.memoryEnabled,
                addHistoryToMessages: template.addHistoryToMessages,
                contextWindowSize: template.contextWindowSize || undefined,
            });
            setMembers(template.members.map(slot => ({
                teamId: '',
                agentId: '',
                role: slot.role as NodeRole,
                joinedAt: '',
                slotLabel: slot.label,
                slotDescription: slot.description,
                isTemplateSlot: true,
            })));
        }
        setWizardStep('form');
    };

    const handleBackToTemplates = () => {
        setTeam({ name: '', description: '', members: [] });
        setMembers([]);
        setLocalEdges([]);
        setManifest({});
        setDirty(false);
        setWizardStep('template');
    };

    // ── Template Slot Agent Assignment ──────────────────────────
    const handleSlotAgentChange = (index: number, agentId: string) => {
        if (agentId && members.some((m, i) => i !== index && m.agentId === agentId)) return;
        const slot = members[index];
        setMembers(prev => {
            const updated = [...prev];
            updated[index] = { ...updated[index], agentId };
            return updated;
        });
        if (slot.role === 'LEADER') {
            setTeam(prev => prev ? { ...prev, leaderId: agentId || undefined } : prev);
        }
        setDirty(true);
    };

    // ── Edge Handlers ───────────────────────────────────────────
    const handleAddEdge = async () => {
        if (!newEdgeSource.trim() || !newEdgeTarget.trim()) return;

        if (isNew || !team?.id) {
            // During creation: collect in local state
            const dup = localEdges.some(e => e.sourceAgentId === newEdgeSource && e.targetAgentId === newEdgeTarget);
            if (dup) return;
            setLocalEdges(prev => [...prev, { sourceAgentId: newEdgeSource.trim(), targetAgentId: newEdgeTarget.trim() }]);
        } else {
            // Edit mode: persist immediately
            try {
                const edge = await orchestrationApi.addTransitionEdge(team.id, {
                    sourceAgentId: newEdgeSource.trim(),
                    targetAgentId: newEdgeTarget.trim()
                });
                setTransitionEdges(prev => [...prev, edge]);
            } catch (err) {
                console.error("Failed to add transition edge", err);
                setError('Failed to add edge. It may already exist.');
                return;
            }
        }
        setNewEdgeSource('');
        setNewEdgeTarget('');
        setDirty(true);
    };

    const handleRemoveEdge = async (edgeId: string) => {
        if (!team?.id) return;
        try {
            await orchestrationApi.removeTransitionEdge(team.id, edgeId);
            setTransitionEdges(prev => prev.filter(e => e.id !== edgeId));
            setDirty(true);
        } catch (err) {
            console.error("Failed to remove transition edge", err);
        }
    };

    const handleRemoveLocalEdge = (index: number) => {
        setLocalEdges(prev => prev.filter((_, i) => i !== index));
        setDirty(true);
    };

    // ── Save ────────────────────────────────────────────────────
    const handleSave = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!team) return;
        try {
            setSaving(true);
            setError(null);
            setSuccess(null);
            let savedTeam: Team;
            if (team.id) {
                savedTeam = await orchestrationApi.updateTeam(team.id, team);
            } else {
                savedTeam = await orchestrationApi.createTeam(team);
            }

            const finalTeamId = savedTeam.id;

            // Save members
            const filledMembers = members.filter(m => m.agentId);
            const toAdd = filledMembers.filter(m => !originalMembers.find(o => o.agentId === m.agentId));
            const toRemove = originalMembers.filter(o => !filledMembers.find(m => m.agentId === o.agentId));

            for (const rm of toRemove) {
                await orchestrationApi.removeTeamMember(finalTeamId, rm.agentId);
            }
            // 2+ new members: one batched call instead of N round-trips.
            // Single add: keep the per-member endpoint (lighter response, same semantics).
            if (toAdd.length >= 2) {
                await orchestrationApi.bulkAddMembers(
                    finalTeamId,
                    toAdd.map(m => ({ agentId: m.agentId, role: m.role })),
                );
            } else {
                for (const add of toAdd) {
                    await orchestrationApi.addTeamMember(finalTeamId, { ...add, teamId: finalTeamId });
                }
            }

            // Persist local edges (collected during creation)
            for (const edge of localEdges) {
                await orchestrationApi.addTransitionEdge(finalTeamId, edge);
            }

            // Save manifest
            if (manifest && finalTeamId) {
                await orchestrationApi.updateTeamManifest(finalTeamId, manifest);
            }

            setDirty(false);
            setSuccess('Team saved successfully.');

            if (isNew) {
                navigate(`/teams/${finalTeamId}`, { replace: true });
            } else {
                await loadTeam(finalTeamId);
                setLocalEdges([]);
            }
        } catch (err: any) {
            console.error('Failed to save team', err);
            setError(err?.message || 'Failed to save team.');
        } finally {
            setSaving(false);
        }
    };

    const handleAddMember = () => {
        if (!newAgentId.trim()) return;
        if (members.find(m => m.agentId === newAgentId.trim())) return;
        setMembers([...members, {
            teamId: team?.id || '',
            agentId: newAgentId.trim(),
            role: newAgentRole,
            joinedAt: new Date().toISOString()
        }]);
        setNewAgentId('');
        setNewAgentRole('MEMBER');
        setDirty(true);
    };

    const handleRemoveMember = (agentId: string) => {
        setMembers(members.filter(m => m.agentId !== agentId));
        setDirty(true);
    };

    // ── Helpers ─────────────────────────────────────────────────
    const setTeamField = (updates: Partial<Team>) => {
        setTeam(prev => prev ? { ...prev, ...updates } : prev);
        setDirty(true);
    };

    // Member agent IDs for edge pickers (only show team members)
    const memberAgentOptions = members
        .filter(m => m.agentId)
        .map(m => {
            const agent = availableAgents.find(a => a.id === m.agentId);
            return { value: m.agentId, label: agent?.name || m.agentId, sublabel: m.agentId };
        });

    // ── Loading State ───────────────────────────────────────────
    if (loading || !team) return (
        <div className="space-y-2">
            {[1, 2, 3, 4].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
        </div>
    );

    // ── Template Picker Step ────────────────────────────────────
    if (wizardStep === 'template' && isNew) {
        return (
            <PageContainer variant="form">
                <div className="flex items-center gap-4">
                    <Button variant="ghost" size="sm" className="shrink-0" onClick={() => navigate('/teams')}>
                        <LuArrowLeft className="w-4 h-4" />
                    </Button>
                    <PageHeader
                        icon={LuLayoutGrid}
                        title="Choose a Team Template"
                        subtitle="Select a pre-configured template to get started quickly, or start from scratch."
                    />
                </div>

                {templateError && (
                    <Alert severity="warning">{templateError}</Alert>
                )}

                <TemplatePickerGrid
                    items={templates.map((template): TemplateCardItem => ({
                        id: template.id,
                        icon: TEMPLATE_ICONS[template.icon] || '\u{1F4E6}',
                        name: template.name,
                        description: template.description,
                        badge: template.teamMode ? (TEAM_MODE_LABELS[template.teamMode] || template.teamMode) : undefined,
                        metadata: template.members.length > 0
                            ? `${template.members.length} member slot${template.members.length !== 1 ? 's' : ''}`
                            : undefined,
                    }))}
                    onSelect={(id) => {
                        const tpl = templates.find(t => t.id === id);
                        if (tpl) handleSelectTemplate(tpl);
                    }}
                />
            </PageContainer>
        );
    }

    // ── Form Step ───────────────────────────────────────────────
    return (
        <PageContainer variant="form">
            <div className="flex items-center gap-4">
                <Button variant="ghost" size="sm" className="shrink-0" onClick={() => navigate('/teams')}>
                    <LuArrowLeft className="w-4 h-4" />
                </Button>
                <PageHeader
                    icon={LuUsers}
                    title={isNew ? 'Create Agent Team' : 'Team Details'}
                    subtitle={
                        dirty
                            ? 'Unsaved changes \u2014 Configure orchestration logic, meta-agent properties, and FinOps constraints.'
                            : 'Configure orchestration logic, meta-agent properties, and FinOps constraints.'
                    }
                    actions={
                        <>
                            {isNew && templates.length > 0 && (
                                <Button variant="ghost" size="sm" className="gap-1.5" onClick={handleBackToTemplates}>
                                    <LuLayoutGrid className="w-3.5 h-3.5" /> Templates
                                </Button>
                            )}
                            <Button variant="ghost" size="sm" onClick={() => navigate('/teams')} disabled={saving}>Cancel</Button>
                            <Button size="sm" className="gap-1.5" onClick={handleSave} disabled={saving}>
                                {saving ? <span className="loading loading-spinner loading-xs" /> : <LuSave className="w-4 h-4" />}
                                Save
                            </Button>
                        </>
                    }
                />
            </div>

            {error && <Alert severity="error" title="Error" description={error} dismissible onClose={() => setError(null)} />}
            {success && <Alert severity="success" title="Saved" description={success} dismissible onClose={() => setSuccess(null)} />}

            {!isNew && health && <TeamHealthBar health={health} />}

            <form onSubmit={handleSave} className="space-y-6">

                {/* 1. General Settings */}
                <GeneralSettingsSection team={team} setTeamField={setTeamField} agentOptions={agentOptions} />

                {/* 2. Meta-Agent Configuration */}
                <MetaAgentSection team={team} setTeamField={setTeamField} modelOptions={modelOptions} />

                {/* 3. Members Roster */}
                <MembersRosterSection
                    members={members}
                    availableAgents={availableAgents}
                    agentOptions={agentOptions}
                    team={team}
                    newAgentId={newAgentId}
                    setNewAgentId={setNewAgentId}
                    newAgentRole={newAgentRole}
                    setNewAgentRole={setNewAgentRole}
                    handleSlotAgentChange={handleSlotAgentChange}
                    handleAddMember={handleAddMember}
                    handleRemoveMember={handleRemoveMember}
                />

                {/* 4. DAG Transition Constraints — visible during both create and edit */}
                <section className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6 space-y-6">
                    <Typography.Heading level={3} className="text-(--theme-foreground) font-semibold">Transition DAG Constraints</Typography.Heading>
                    <p className="text-xs text-(--theme-muted)">Define explicit routing paths between members. The engine strictly rejects violations.</p>

                    {/* Persisted edges (edit mode) */}
                    {transitionEdges.length === 0 && localEdges.length === 0 && (
                        <div className="text-center p-8 bg-(--theme-background) rounded-lg border-2 border-dashed border-(--theme-muted)/20">
                            <span className="text-(--theme-muted) text-sm">No constraints defined. Free-flow routing is permitted.</span>
                        </div>
                    )}
                    {transitionEdges.map(edge => (
                        <div key={edge.id} className="flex items-center justify-between p-4 bg-obsidian-elevated rounded-lg border border-(--theme-muted)/10">
                            <div className="flex items-center gap-4 text-sm font-mono">
                                <span className="font-bold text-primary">{edge.sourceAgentId}</span>
                                <span className="text-(--theme-muted)">\u279C</span>
                                <span className="font-bold text-info">{edge.targetAgentId}</span>
                            </div>
                            <Button variant="ghost" size="sm" className="text-error hover:bg-error/10" onClick={() => handleRemoveEdge(edge.id)}>
                                <LuTrash2 className="w-3.5 h-3.5" />
                            </Button>
                        </div>
                    ))}
                    {/* Local edges (creation mode) */}
                    {localEdges.map((edge, idx) => (
                        <div key={`local-${idx}`} className="flex items-center justify-between p-4 bg-obsidian-elevated rounded-lg border border-(--theme-muted)/10">
                            <div className="flex items-center gap-4 text-sm font-mono">
                                <span className="font-bold text-primary">{edge.sourceAgentId}</span>
                                <span className="text-(--theme-muted)">\u279C</span>
                                <span className="font-bold text-info">{edge.targetAgentId}</span>
                                <Badge variant="ghost" className="text-[10px]">Pending save</Badge>
                            </div>
                            <Button variant="ghost" size="sm" className="text-error hover:bg-error/10" onClick={() => handleRemoveLocalEdge(idx)}>
                                <LuTrash2 className="w-3.5 h-3.5" />
                            </Button>
                        </div>
                    ))}

                    {/* Add New Edge */}
                    <div className="bg-obsidian-elevated p-5 rounded-lg border border-(--theme-muted)/10 space-y-4">
                        <div className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider">Register New Edge</div>
                        <div className="flex flex-col sm:flex-row gap-4 items-end">
                            <div className="flex-1 w-full">
                                <SearchableSelect
                                    label="Source Agent"
                                    value={newEdgeSource}
                                    onChange={setNewEdgeSource}
                                    options={memberAgentOptions}
                                    placeholder="Select source..."
                                />
                            </div>
                            <div className="flex-1 w-full">
                                <SearchableSelect
                                    label="Target Agent"
                                    value={newEdgeTarget}
                                    onChange={setNewEdgeTarget}
                                    options={memberAgentOptions}
                                    placeholder="Select target..."
                                />
                            </div>
                            <Button type="button" variant="outline" size="sm" className="gap-1.5 w-full sm:w-auto" onClick={handleAddEdge}
                                disabled={!newEdgeSource.trim() || !newEdgeTarget.trim()}>
                                <LuPlus className="w-4 h-4" /> Bind
                            </Button>
                        </div>
                    </div>
                </section>

                {/* 5. FinOps & Constraints — visible during both create and edit */}
                <FinOpsSection manifest={manifest} setManifest={setManifest} setDirty={setDirty} />

            </form>

            {/* Unsaved Changes Dialog */}
            <Dialog
                isOpen={leaveOpen}
                setIsOpen={(open) => {
                    setLeaveOpen(open);
                    if (!open && blocker.state === 'blocked') blocker.reset();
                }}
                title="Unsaved Changes"
                content="You have unsaved changes. Are you sure you want to leave? Changes will be lost."
                severity="warning"
                confirmLabel="Leave"
                cancelLabel="Stay"
                onConfirm={() => {
                    setLeaveOpen(false);
                    setDirty(false);
                    pendingNavigate.current?.();
                }}
                onCancel={() => {
                    setLeaveOpen(false);
                    if (blocker.state === 'blocked') blocker.reset();
                }}
            />
        </PageContainer>
    );
};
