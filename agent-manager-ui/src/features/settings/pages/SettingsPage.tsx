import React, { useState, useEffect, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useBlocker } from 'react-router-dom';
import { settingsApi } from '../api/settings-api';
import { dataRetentionApi } from '../api/dataRetentionApi';
import { useAppDefaults } from '../../../shared/hooks/useAppDefaults';
import { configApi } from '../../../shared/api/configApi';
import type { AppConfig } from '../../../shared/api/configApi';

import { Typography } from '../../../shared/components/ui/Typography';
import { Card } from '../../../shared/components/ui/Card';
import { Input } from '../../../shared/components/ui/Input';
import { Button } from '../../../shared/components/ui/Button';
import { Select } from '../../../shared/components/ui/Select';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { LuSettings, LuTrash2 } from 'react-icons/lu';
import { ModelsApi } from '../../models/api/models-api';
import type { ModelConfig } from '../../models/types/models.types';
import { AgentsApi } from '../../agents/api/agents-api';
import type { AgentConfig } from '../../../shared/types/api';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { SettingsImpactModal } from '../components/SettingsImpactModal';

export const SettingsPage: React.FC = () => {
    const { defaults } = useAppDefaults();
    const { data: appConfig } = useQuery({
        queryKey: ['config'],
        queryFn: (): Promise<AppConfig> => configApi.getConfig(),
        staleTime: 300_000,
    });

    const [purging, setPurging] = useState(false);
    const [purgeResult, setPurgeResult] = useState<Record<string, number> | null>(null);

    const [reembedding, setReembedding] = useState(false);
    const [reembedResult, setReembedResult] = useState<string | null>(null);

    const [maxPages, setMaxPages] = useState<string>("");
    const [compressionChars, setCompressionChars] = useState<string>("");
    const [summarizationTurns, setSummarizationTurns] = useState<string>("");
    const [defaultRouter, setDefaultRouter] = useState<string>("");
    const [defaultFast, setDefaultFast] = useState<string>("");
    const [defaultHeavy, setDefaultHeavy] = useState<string>("");
    const [defaultEmbedding, setDefaultEmbedding] = useState<string>("");

    // Convention over Configuration: Global Agent Defaults
    const [defaultTemperature, setDefaultTemperature] = useState<string>("");
    const [defaultTopP, setDefaultTopP] = useState<string>("");
    const [defaultFinOpsRiskTier, setDefaultFinOpsRiskTier] = useState<string>("");
    const [defaultSecurityTier, setDefaultSecurityTier] = useState<string>("");
    const [defaultMaxConcurrent, setDefaultMaxConcurrent] = useState<string>("");

    const [sessionsRetentionDays, setSessionsRetentionDays] = useState<string>("");
    const [runsRetentionDays, setRunsRetentionDays] = useState<string>("");
    const [auditRetentionDays, setAuditRetentionDays] = useState<string>("");
    const [alertsRetentionDays, setAlertsRetentionDays] = useState<string>("");

    const [models, setModels] = useState<ModelConfig[]>([]);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [leaveOpen, setLeaveOpen] = useState(false);
    const pendingNavigate = useRef<(() => void) | null>(null);

    // Navigation guard — warn on unsaved changes
    const blocker = useBlocker(dirty);

    useEffect(() => {
        if (blocker.state === 'blocked') {
            setLeaveOpen(true);
            pendingNavigate.current = () => blocker.proceed();
        }
    }, [blocker.state]);

    const markDirty = () => setDirty(true);

    // Blast Radius: impact analysis before saving
    const [showImpactModal, setShowImpactModal] = useState(false);
    const [impactedAgents, setImpactedAgents] = useState<AgentConfig[]>([]);
    const [, setImpactLoading] = useState(false);

    // Populate form state from API defaults once loaded
    useEffect(() => {
        if (!defaults) return;
        setMaxPages(String(defaults.crawlerMaxPages));
        setCompressionChars(String(defaults.compressionThresholdChars));
        setSummarizationTurns(String(defaults.summarizationThresholdTurns));
        setDefaultRouter(defaults.defaultModelRouter);
        setDefaultFast(defaults.defaultModelFast);
        setDefaultHeavy(defaults.defaultModelHeavy);
        setDefaultEmbedding(defaults.defaultModelEmbedding);
        setDefaultTemperature(String(defaults.defaultTemperature));
        setDefaultTopP(String(defaults.defaultTopP));
        setDefaultFinOpsRiskTier(defaults.defaultFinOpsRiskTier);
        setDefaultSecurityTier(String(defaults.defaultSecurityTier));
        setDefaultMaxConcurrent(String(defaults.defaultMaxConcurrent));
    }, [defaults]);

    useEffect(() => {
        dataRetentionApi.getPolicies()
            .then(data => {
                if (data.sessions_days != null) setSessionsRetentionDays(String(data.sessions_days));
                if (data.runs_days != null) setRunsRetentionDays(String(data.runs_days));
                if (data.audit_days != null) setAuditRetentionDays(String(data.audit_days));
                if (data.alerts_days != null) setAlertsRetentionDays(String(data.alerts_days));
            })
            .catch(() => {});
    }, []);

    const handlePurge = async () => {
        if (!confirm('Run data retention purge now? Old records will be permanently deleted.')) return;
        setPurging(true);
        try {
            const result = await dataRetentionApi.purge();
            setPurgeResult(result);
        } catch (err) {
            console.error('Purge failed', err);
        } finally {
            setPurging(false);
        }
    };

    const handleReembed = async () => {
        if (!confirm("Re-embed all of this org's knowledge-base and memory vectors under the current embedding model? This may take a while and incur embedding cost.")) return;
        setReembedding(true);
        setReembedResult(null);
        try {
            const r = await settingsApi.reembedVectors();
            setReembedResult(`Re-embedded ${r.reembedded} of ${r.scanned} vectors (${r.dimensions}d).`);
        } catch (err) {
            console.error('Re-embed failed', err);
            setReembedResult('Re-embed failed — see console for details.');
        } finally {
            setReembedding(false);
        }
    };

    // Load models for dropdowns
    useEffect(() => {
        ModelsApi.getModels()
            .then(data => setModels(data.content))
            .catch(err => console.error("Failed to load models:", err));
    }, []);

    const settingsPayload = {
        'crawler.maxPages': maxPages,
        'DEFAULT_MODEL_ROUTER': defaultRouter,
        'DEFAULT_MODEL_FAST': defaultFast,
        'DEFAULT_MODEL_HEAVY': defaultHeavy,
        'DEFAULT_MODEL_EMBEDDING': defaultEmbedding,
        'COMPRESSION_THRESHOLD_CHARS': compressionChars,
        'SUMMARIZATION_THRESHOLD_TURNS': summarizationTurns,
        'DEFAULT_TEMPERATURE': defaultTemperature,
        'DEFAULT_TOP_P': defaultTopP,
        'DEFAULT_FINOPS_RISK_TIER': defaultFinOpsRiskTier,
        'DEFAULT_SECURITY_TIER': defaultSecurityTier,
        'DEFAULT_MAX_CONCURRENT_EXECUTIONS': defaultMaxConcurrent,
        // Keys match SettingsService.KEY_RETENTION_* constants
        'app.retention.sessions-days': sessionsRetentionDays,
        'app.retention.runs-days': runsRetentionDays,
        'app.retention.audit-days': auditRetentionDays,
        'app.retention.alerts-days': alertsRetentionDays,
    };

    const doSave = async () => {
        setSaving(true);
        try {
            await settingsApi.updateSettings(settingsPayload);
            setShowImpactModal(false);
            setDirty(false);
        } catch (error) {
            console.error("Failed to save settings:", error);
        } finally {
            setSaving(false);
        }
    };

    // Blast Radius: fetch agents that inherit defaults, then show impact modal
    const handleSaveSettings = async () => {
        setImpactLoading(true);
        try {
            const agents = await AgentsApi.getAgents();
            // Agents that don't override temperature/topP/security/finops inherit global defaults
            const affected = (Array.isArray(agents) ? agents : []).filter((a: AgentConfig) =>
                a.active !== false && (
                    a.temperature == null ||
                    a.topP == null ||
                    a.finOpsRiskTier == null ||
                    a.securityTier == null ||
                    a.maxConcurrentExecutions == null
                )
            );
            setImpactedAgents(affected);

            if (affected.length > 0) {
                setShowImpactModal(true);
            } else {
                await doSave();
            }
        } catch {
            // If agent fetch fails, save anyway
            await doSave();
        } finally {
            setImpactLoading(false);
        }
    };
    
    const chatModelOptions = models.filter(m => m.modelType === 'CHAT' || !m.modelType).map(m => ({ label: `${m.name} (${m.modelName || m.id})`, value: m.id }));
    const embeddingModelOptions = models.filter(m => m.modelType === 'EMBEDDING').map(m => ({ label: `${m.name} (${m.modelName || m.id})`, value: m.id }));

    // Ensure the currently-selected value appears in the dropdown even if not in the models table
    for (const [val, opts] of [
        [defaultRouter, chatModelOptions], [defaultFast, chatModelOptions],
        [defaultHeavy, chatModelOptions], [defaultEmbedding, embeddingModelOptions],
    ] as [string, typeof chatModelOptions][]) {
        if (val && !opts.find(o => o.value === val)) {
            opts.push({ label: `${val} (Current)`, value: val });
        }
    }

    return (
             <PageContainer variant="form">
                <PageHeader
                    icon={LuSettings}
                    title="Settings"
                    subtitle="Configure application preferences and connections."
                />

                <Card>
                    <Card.Header>
                        <Typography.Heading level={3}>General Preferences</Typography.Heading>
                    </Card.Header>
                    <Card.Body className="space-y-6">
                        <Select 
                            label="Theme" 
                            options={[
                                { label: 'System Default', value: 'system' },
                                { label: 'Light', value: 'light' },
                                { label: 'Dark', value: 'dark' },
                            ]}
                        />
                         <Select 
                            label="Language" 
                            options={[
                                { label: 'English (US)', value: 'en-US' },
                            ]}
                            disabled
                        />
                    </Card.Body>
                </Card>

                <Card>
                    <Card.Header>
                        <Typography.Heading level={3}>Web Scraper Configuration</Typography.Heading>
                    </Card.Header>
                    <Card.Body className="space-y-6">
                        <Input
                            label="Max Pages Per Scrape"
                            type="number"
                            min={1}
                            max={5000}
                            value={maxPages}
                            onChange={(e) => { setMaxPages(e.target.value); markDirty(); }}
                            description="The maximum number of pages the background job is allowed to crawl when bulk ingesting a documentation site."
                            helpText="Higher values increase ingestion time and storage costs. Recommended: 50-500 for most documentation sites."
                        />
                    </Card.Body>
                </Card>

                <Card>
                    <Card.Header>
                        <Typography.Heading level={3}>Global Optimization Boundaries</Typography.Heading>
                    </Card.Header>
                    <Card.Body className="space-y-6">
                        <Input
                            label="Payload Compression Threshold (Chars)"
                            type="number"
                            min={100}
                            value={compressionChars}
                            onChange={(e) => { setCompressionChars(e.target.value); markDirty(); }}
                            description="Global default point at which tool payloads trigger automatic LLM summarization. Higher targets allow models to process more raw data at scale."
                            helpText="Agents can override this per-agent. Lower values reduce token usage but may lose context fidelity."
                        />
                        <Input
                            label="Session Amnesia Depth (Turns)"
                            type="number"
                            min={5}
                            value={summarizationTurns}
                            onChange={(e) => { setSummarizationTurns(e.target.value); markDirty(); }}
                            description="Global default turn limit before conversation history is converted into a condensed memory blob."
                            helpText="Lower values save tokens but reduce conversational recall. Typical range: 10-50 turns."
                        />
                    </Card.Body>
                </Card>

                <Card>
                    <Card.Header>
                        <Typography.Heading level={3}>Agent Defaults (Convention over Configuration)</Typography.Heading>
                        <Typography.Text className="text-xs text-(--theme-muted) mt-1">
                            These defaults are inherited by all agents that don't explicitly override them. Reduces per-agent configuration burden by 80%.
                        </Typography.Text>
                    </Card.Header>
                    <Card.Body className="space-y-6">
                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                            <Input
                                label="Default Temperature"
                                type="number"
                                min={0}
                                max={2}
                                value={defaultTemperature}
                                onChange={(e) => { setDefaultTemperature(e.target.value); markDirty(); }}
                                description="Inherited by agents with no explicit temperature set."
                                helpText="0 = deterministic, 1 = balanced creativity, 2 = maximum randomness. Most agents work best at 0.3-0.7."
                            />
                            <Input
                                label="Default Top P"
                                type="number"
                                min={0}
                                max={1}
                                value={defaultTopP}
                                onChange={(e) => { setDefaultTopP(e.target.value); markDirty(); }}
                                description="Inherited by agents with no explicit topP set."
                                helpText="Nucleus sampling threshold. 1.0 considers all tokens; lower values focus on high-probability tokens."
                            />
                        </div>
                        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                            <Select
                                label="Default FinOps Risk Tier"
                                options={[
                                    { label: 'Unrestricted (No limits)', value: 'UNRESTRICTED' },
                                    { label: 'Low Risk (5M tokens)', value: 'LOW_RISK' },
                                    { label: 'Moderate Risk (1M tokens)', value: 'MODERATE_RISK' },
                                    { label: 'Strict (500K tokens)', value: 'STRICT' },
                                    { label: 'Critical (100K tokens)', value: 'CRITICAL' },
                                ]}
                                value={defaultFinOpsRiskTier}
                                onChange={(e) => { setDefaultFinOpsRiskTier(e.target.value); markDirty(); }}
                                description="Agents inherit this tier if they don't set a budget."
                                helpText="Controls the token budget ceiling. STRICT and CRITICAL tiers trigger alerts before exhaustion."
                            />
                            <Select
                                label="Default Security Tier"
                                options={[
                                    { label: 'Tier 1 — Read-only / Low Privilege', value: '1' },
                                    { label: 'Tier 2 — Standard Operations', value: '2' },
                                    { label: 'Tier 3 — Elevated / Requires Approval', value: '3' },
                                ]}
                                value={defaultSecurityTier}
                                onChange={(e) => { setDefaultSecurityTier(e.target.value); markDirty(); }}
                                description="Inherited by agents with no explicit security tier."
                                helpText="Tier 3 agents require human-in-the-loop approval for sensitive operations."
                            />
                            <Input
                                label="Default Max Concurrent Executions"
                                type="number"
                                min={1}
                                max={100}
                                value={defaultMaxConcurrent}
                                onChange={(e) => { setDefaultMaxConcurrent(e.target.value); markDirty(); }}
                                description="Protects system from parallel overload."
                                helpText="Limits how many instances of the same agent can run simultaneously. Prevents runaway costs."
                            />
                        </div>
                    </Card.Body>
                </Card>

                <Card>
                    <Card.Header>
                        <Typography.Heading level={3}>Model Defaults & Roles</Typography.Heading>
                    </Card.Header>
                    <Card.Body className="space-y-6">
                        <Select
                            label="Default Router Model"
                            options={chatModelOptions}
                            value={defaultRouter}
                            onChange={(e) => { setDefaultRouter(e.target.value); markDirty(); }}
                            description="Used for fast, inexpensive routing and classification tasks."
                            helpText="Choose a fast, low-cost model. This handles intent classification and team routing — not complex reasoning."
                        />
                        <Select
                            label="Default Fast Compression Model"
                            options={chatModelOptions}
                            value={defaultFast}
                            onChange={(e) => { setDefaultFast(e.target.value); markDirty(); }}
                            description="Used for automated background tasks like context compression, payload truncation, and semantic reflection."
                            helpText="Runs automatically in background. Optimize for speed and cost over reasoning quality."
                        />
                        <Select
                            label="Default Heavy Model"
                            options={chatModelOptions}
                            value={defaultHeavy}
                            onChange={(e) => { setDefaultHeavy(e.target.value); markDirty(); }}
                            description="Used for complex reasoning and standard agent tasks."
                            helpText="The primary model powering agent interactions. Choose the best quality model your budget allows."
                        />
                        <Select
                            label="Default Embedding Model"
                            options={embeddingModelOptions}
                            value={defaultEmbedding}
                            onChange={(e) => { setDefaultEmbedding(e.target.value); markDirty(); }}
                            description="Used for document ingestion and semantic search."
                            helpText="Must be an embedding-type model. Changing this after ingestion may require re-indexing existing documents."
                        />
                        <div className="flex items-center justify-between gap-4 rounded-lg border border-theme-muted/30 p-3">
                            <div className="text-sm text-theme-muted">
                                After changing the embedding model, re-embed existing knowledge-base &amp; memory
                                vectors so retrieval uses the new model.
                                {reembedResult && <span className="block pt-1 text-theme-foreground">{reembedResult}</span>}
                            </div>
                            <Button variant="outline" size="sm" onClick={handleReembed} disabled={reembedding}>
                                {reembedding
                                    ? <span className="loading loading-spinner loading-xs"></span>
                                    : 'Re-embed vectors now'}
                            </Button>
                        </div>
                        <div className="flex justify-end pt-4">
                            <Button 
                                variant="primary" 
                                onClick={handleSaveSettings} 
                                disabled={saving}
                            >
                                {saving ? "Saving..." : "Save Settings"}
                            </Button>
                        </div>
                    </Card.Body>
                </Card>

                <Card>
                    <Card.Header>
                        <Typography.Heading level={3}>Data Retention</Typography.Heading>
                        <Typography.Text className="text-xs text-(--theme-muted) mt-1">
                            Automatic purge runs daily at 3:00 AM. Changes saved here take effect on the next scheduled run.
                        </Typography.Text>
                    </Card.Header>
                    <Card.Body className="space-y-4">
                        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                            <Input
                                label="Sessions (days)"
                                type="number"
                                min={1}
                                value={sessionsRetentionDays}
                                onChange={(e) => { setSessionsRetentionDays(e.target.value); markDirty(); }}
                            />
                            <Input
                                label="Runs (days)"
                                type="number"
                                min={1}
                                value={runsRetentionDays}
                                onChange={(e) => { setRunsRetentionDays(e.target.value); markDirty(); }}
                            />
                            <Input
                                label="Audit Logs (days)"
                                type="number"
                                min={1}
                                value={auditRetentionDays}
                                onChange={(e) => { setAuditRetentionDays(e.target.value); markDirty(); }}
                            />
                            <Input
                                label="Alerts (days)"
                                type="number"
                                min={1}
                                value={alertsRetentionDays}
                                onChange={(e) => { setAlertsRetentionDays(e.target.value); markDirty(); }}
                            />
                        </div>
                        {purgeResult && (
                            <div className="bg-obsidian-surface border border-obsidian-stroke rounded-lg p-3 text-sm text-theme-muted">
                                Purge completed: {Object.entries(purgeResult).map(([k, v]) => `${k}: ${v}`).join(', ')}
                            </div>
                        )}
                        <div className="flex justify-end">
                            <Button variant="outline" size="sm" onClick={handlePurge} disabled={purging}>
                                {purging ? <span className="loading loading-spinner loading-xs"></span> : <><LuTrash2 size={14} /> Run Purge Now</>}
                            </Button>
                        </div>
                    </Card.Body>
                </Card>

                <Card>
                     <Card.Header>
                        <Typography.Heading level={3}>Backend Connection</Typography.Heading>
                    </Card.Header>
                    <Card.Body className="space-y-6">
                        <Input 
                            label="API Endpoint" 
                            placeholder="http://localhost:8080/api" 
                            defaultValue="/api" 
                            disabled
                            description="The generic API proxy method is currently active."
                        />
                        <div className="flex justify-end">
                            <Button variant="outline">Test Connection</Button>
                        </div>
                    </Card.Body>
                </Card>

                <div className="flex justify-between items-center text-xs text-(--theme-muted) pt-8">
                    <span>AgentManager UI</span>
                    <span>Backend v{appConfig?.version ?? '...'}</span>
                </div>

                {/* Blast Radius Impact Modal */}
                {showImpactModal && (
                    <SettingsImpactModal
                        impactedAgents={impactedAgents}
                        saving={saving}
                        onCancel={() => setShowImpactModal(false)}
                        onConfirm={doSave}
                    />
                )}

                {/* Unsaved Changes Navigation Guard */}
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
