import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { AgentConfig, PaginatedResponse } from '../../../../shared/types/api';
import type { ColumnDef } from '@tanstack/react-table';
import { AgentAdminApi } from '../../api/adminApi';
import { AgentsApi } from '../../api/agents-api';
import { JobsApi, TERMINAL_JOB_STATUSES } from '../../../../shared/api/jobs-api';
import { AgentEditModal } from './AgentEditModal';
import { buildAgentAdminColumns } from './agentAdminColumns';
import { useAppDefaults } from '../../../../shared/hooks/useAppDefaults';
import { PageHeader } from '../../../../shared/components/ui/PageHeader';
import { Alert } from '../../../../shared/components/ui/Alert';
import { Button } from '../../../../shared/components/ui/Button';
import { DataTable } from '../../../../shared/components/ui/DataTable';
import {
    LuShieldCheck, LuPlus, LuUpload, LuTrash2, LuExternalLink,
    LuToggleLeft, LuToggleRight, LuDownload, LuOctagonAlert,
} from 'react-icons/lu';
import { incidentResponseApi } from '../../api/incidentResponseApi';
import { PageContainer } from '../../../../shared/components/ui/PageContainer';

export const AgentAdminDashboardPage: React.FC = () => {
    const navigate = useNavigate();
    const { defaults } = useAppDefaults();
    const observabilityUrl = defaults?.raw?.['OBSERVABILITY_URL'] || '';
    const [agentsPage, setAgentsPage] = useState<PaginatedResponse<AgentConfig> | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [actionMessage, setActionMessage] = useState<{ text: string, type: 'success' | 'error' | 'info' | 'warning' } | null>(null);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [selectedAgent, setSelectedAgent] = useState<AgentConfig | null>(null);
    const fileInputRef = React.useRef<HTMLInputElement>(null);

    // Pagination
    const PAGE_SIZE = 20;
    const [pageIndex, setPageIndex] = useState(0);

    // Bulk selection
    const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
    const [bulkLoading, setBulkLoading] = useState(false);

    const loadAgents = async () => {
        try {
            setLoading(true);
            const data = await AgentAdminApi.getAgents(pageIndex, PAGE_SIZE, true);
            setAgentsPage(data);
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Error loading agents');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        loadAgents();
    }, [pageIndex]);

    const showMessage = (text: string, type: 'success' | 'error' | 'info' | 'warning' = 'info') => {
        setActionMessage({ text, type });
        setTimeout(() => setActionMessage(null), 5000);
    };

    const handleCreateClick = () => {
        navigate('/agents/new');
    };

    const handleEditClick = (agent: AgentConfig) => {
        setSelectedAgent(agent);
        setIsModalOpen(true);
    };

    const handleSave = async (agent: AgentConfig) => {
        if (selectedAgent) {
            await AgentAdminApi.updateAgent(agent.agentId, agent);
            showMessage('Agent updated successfully', 'success');
        } else {
            await AgentAdminApi.createAgent(agent);
            showMessage('Agent created successfully', 'success');
        }
        await loadAgents();
    };

    const handleImportClick = () => {
        fileInputRef.current?.click();
    };

    const handleFileChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        if (!file) return;

        try {
            const text = await file.text();
            const config = JSON.parse(text) as AgentConfig;
            await AgentAdminApi.importAgent(config);
            showMessage('Agent imported successfully', 'success');
            await loadAgents();
        } catch (err) {
            console.error('Failed to import agent', err);
            setError('Failed to import agent: ' + (err instanceof Error ? err.message : String(err)));
        }

        event.target.value = '';
    };

    const handleClearCache = async () => {
        try {
            const msg = await AgentsApi.clearCache();
            showMessage(msg || 'Cache cleared', 'success');
            await loadAgents();
        } catch (err) {
            console.error('Failed to clear cache', err);
            showMessage('Failed to clear cache', 'error');
        }
    };

    const toggleSelectAll = () => {
        const allIds = agentsPage?.content.map(a => a.agentId) ?? [];
        if (allIds.every(id => selectedIds.has(id))) {
            setSelectedIds(new Set());
        } else {
            setSelectedIds(new Set(allIds));
        }
    };

    const toggleSelectOne = (id: string) => {
        setSelectedIds(prev => {
            const next = new Set(prev);
            next.has(id) ? next.delete(id) : next.add(id);
            return next;
        });
    };

    const pollJob = async (jobId: string) => {
        while (true) {
            const job = await JobsApi.getJobStatus(jobId);
            if (TERMINAL_JOB_STATUSES.includes(job.status)) return job;
            await new Promise(resolve => setTimeout(resolve, 3000));
        }
    };

    const handleBulkAction = async (action: 'ENABLE' | 'DISABLE' | 'DELETE') => {
        if (selectedIds.size === 0) return;
        const ids = Array.from(selectedIds);
        if (action === 'DELETE' && !confirm(`Delete ${ids.length} agent(s)? This cannot be undone.`)) return;
        try {
            setBulkLoading(true);
            const { jobId } = await AgentAdminApi.bulkAction(ids, action);
            showMessage(`${action} queued for ${ids.length} agent(s)…`, 'info');
            const job = await pollJob(jobId);
            if (job.status === 'COMPLETED') {
                showMessage(`${action}: ${ids.length} agent(s) affected`, 'success');
                setSelectedIds(new Set());
                await loadAgents();
            } else {
                showMessage(`Bulk ${action.toLowerCase()} failed: ${job.errorMessage ?? 'unknown error'}`, 'error');
            }
        } catch (err) {
            showMessage(`Bulk ${action.toLowerCase()} failed`, 'error');
        } finally {
            setBulkLoading(false);
        }
    };

    const handleBulkExport = async () => {
        if (selectedIds.size === 0) return;
        try {
            setBulkLoading(true);
            const { jobId } = await AgentAdminApi.bulkExport(Array.from(selectedIds));
            showMessage('Export queued…', 'info');
            const job = await pollJob(jobId);
            if (job.status === 'COMPLETED' && job.result) {
                const agents = JSON.parse(job.result);
                const blob = new Blob([JSON.stringify(agents, null, 2)], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `agents-export-${Date.now()}.json`;
                a.click();
                URL.revokeObjectURL(url);
                showMessage(`Exported ${Array.isArray(agents) ? agents.length : '?'} agent(s)`, 'success');
            } else {
                showMessage(`Export failed: ${job.errorMessage ?? 'unknown error'}`, 'error');
            }
        } catch (err) {
            showMessage('Bulk export failed', 'error');
        } finally {
            setBulkLoading(false);
        }
    };

    const handleLoadKnowledge = async (agentId: string) => {
        try {
            const { jobId } = await AgentsApi.loadKnowledge(agentId);
            showMessage(`Knowledge ingestion queued (job: ${jobId})`, 'info');
        } catch (err) {
            console.error('Failed to load knowledge', err);
            showMessage(`Failed to load knowledge for ${agentId}`, 'error');
        }
    };

    const handleQuarantine = async (agentId: string, name: string) => {
        const reason = prompt(`Reason for quarantining "${name}":`, 'Suspicious behaviour detected');
        if (reason == null) return;
        try {
            await incidentResponseApi.quarantine(agentId, reason.trim() || 'No reason given');
            showMessage(`Agent "${name}" quarantined`, 'warning');
            await loadAgents();
        } catch (err) {
            showMessage(`Quarantine failed: ${err instanceof Error ? err.message : 'unknown error'}`, 'error');
        }
    };

    const handleUnquarantine = async (agentId: string, name: string) => {
        const reason = prompt(`Reason for lifting quarantine on "${name}":`, 'Issue resolved');
        if (reason == null) return;
        try {
            await incidentResponseApi.unquarantine(agentId, reason.trim() || 'No reason given');
            showMessage(`Agent "${name}" unquarantined`, 'success');
            await loadAgents();
        } catch (err) {
            showMessage(`Unquarantine failed: ${err instanceof Error ? err.message : 'unknown error'}`, 'error');
        }
    };

    const handleHaltAllRuns = async () => {
        const reason = prompt('Reason for halting ALL active runs (SUPER_ADMIN only):', 'Emergency stop');
        if (reason == null) return;
        if (!confirm('This will immediately halt ALL active agent runs across the system. Continue?')) return;
        try {
            const result = await incidentResponseApi.haltAllRuns(reason.trim() || 'No reason given');
            showMessage(`Halted ${result.runsCancelled} run(s) across ${result.tenantsAffected} tenant(s)`, 'warning');
        } catch (err) {
            showMessage(`Halt failed: ${err instanceof Error ? err.message : 'unknown error'}`, 'error');
        }
    };

    const allPageIds = agentsPage?.content.map(a => a.agentId) ?? [];
    const allSelected = allPageIds.length > 0 && allPageIds.every(id => selectedIds.has(id));

    // ── Column Definitions ──────────────────────────────────────
    const columns = useMemo<ColumnDef<AgentConfig, unknown>[]>(
        () => buildAgentAdminColumns({
            allSelected,
            selectedIds,
            onToggleSelectAll: toggleSelectAll,
            onToggleSelectOne: toggleSelectOne,
            onEdit: handleEditClick,
            onLoadKnowledge: handleLoadKnowledge,
            onQuarantine: handleQuarantine,
            onUnquarantine: handleUnquarantine,
        }),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [selectedIds, allSelected],
    );

    return (
        <PageContainer variant="dashboard">
            {actionMessage && (
                <Alert severity={actionMessage.type === 'success' ? 'success' : actionMessage.type === 'warning' ? 'warning' : 'error'}>
                    {actionMessage.text}
                </Alert>
            )}

            <input
                type="file"
                accept=".json"
                ref={fileInputRef}
                style={{ display: 'none' }}
                onChange={handleFileChange}
            />

            <PageHeader
                icon={LuShieldCheck}
                title="Agent Administration"
                subtitle="Manage global agent definitions, configurations, and core systems."
                actions={
                    <>
                        {observabilityUrl && (
                            <a href={observabilityUrl} target="_blank" rel="noopener noreferrer">
                                <Button variant="outline" size="sm" className="gap-1.5">
                                    <LuExternalLink className="w-3.5 h-3.5" /> Traces
                                </Button>
                            </a>
                        )}
                        <Button variant="outline" size="sm" onClick={handleClearCache} className="gap-1.5">
                            <LuTrash2 className="w-3.5 h-3.5" /> Clear Cache
                        </Button>
                        <Button variant="outline" size="sm" onClick={handleImportClick} className="gap-1.5">
                            <LuUpload className="w-3.5 h-3.5" /> Import
                        </Button>
                        <Button size="sm" onClick={handleCreateClick} className="gap-1.5">
                            <LuPlus className="w-4 h-4" /> Create Agent
                        </Button>
                        <Button
                            size="sm"
                            variant="outline"
                            className="gap-1.5 text-error border-error/40 hover:bg-error/10"
                            onClick={handleHaltAllRuns}
                            title="Halt all active agent runs (SUPER_ADMIN)"
                        >
                            <LuOctagonAlert className="w-3.5 h-3.5" /> Halt All Runs
                        </Button>
                    </>
                }
            />

            {selectedIds.size > 0 && (
                <div className="flex items-center gap-2 px-3 py-2 bg-obsidian-elevated border border-obsidian-stroke rounded-md">
                    <span className="text-xs text-(--theme-muted) font-mono mr-1">{selectedIds.size} selected</span>
                    <Button size="sm" variant="ghost" className="gap-1.5 text-success" onClick={() => handleBulkAction('ENABLE')} disabled={bulkLoading}>
                        <LuToggleRight className="w-3.5 h-3.5" /> Enable
                    </Button>
                    <Button size="sm" variant="ghost" className="gap-1.5 text-warning" onClick={() => handleBulkAction('DISABLE')} disabled={bulkLoading}>
                        <LuToggleLeft className="w-3.5 h-3.5" /> Disable
                    </Button>
                    <Button size="sm" variant="ghost" className="gap-1.5" onClick={handleBulkExport} disabled={bulkLoading}>
                        <LuDownload className="w-3.5 h-3.5" /> Export
                    </Button>
                    <Button size="sm" variant="ghost" className="gap-1.5 text-error" onClick={() => handleBulkAction('DELETE')} disabled={bulkLoading}>
                        <LuTrash2 className="w-3.5 h-3.5" /> Delete
                    </Button>
                </div>
            )}

            {error && <Alert severity="error" title="Error">{error}</Alert>}

            {loading ? (
                <div className="space-y-2">
                    {[1, 2, 3, 4].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
                </div>
            ) : (
                <DataTable
                    columns={columns}
                    data={agentsPage?.content || []}
                    manualPagination
                    pageIndex={pageIndex}
                    pageSize={PAGE_SIZE}
                    totalElements={agentsPage?.page.totalElements ?? 0}
                    onPageChange={setPageIndex}
                    emptyMessage="No agents found."
                />
            )}

            <AgentEditModal
                isOpen={isModalOpen}
                onClose={() => setIsModalOpen(false)}
                onSave={handleSave}
                agent={selectedAgent}
            />
        </PageContainer>
    );
};
