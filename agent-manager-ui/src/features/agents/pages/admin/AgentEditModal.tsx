import React, { useState, useEffect } from 'react';
import type { AgentConfig, AgentRun, PaginatedResponse, AgentAudit, AgentTopology, DeveloperMetrics } from '../../../../shared/types/api';
import { AgentAdminApi } from '../../api/adminApi';
import { AgentsApi } from '../../api/agents-api';
import { KnowledgeBasesApi } from '../../../knowledge/api/knowledge-bases-api';
import type { KnowledgeBase } from '../../../../shared/types/api';
import { RunStatus } from '../../../../shared/types/enums';
import { ReactFlow, Controls, Background } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { MarkdownEditor } from '../../../../shared/components/ui/MarkdownEditor';
import { AgentPiiPolicySelector } from '../../components/AgentPiiPolicySelector';
import { useUnsavedChangesGuard } from '../../../../shared/hooks/useUnsavedChangesGuard';
import { Dialog } from '../../../../shared/components/ui/Dialog';
import { DataTable } from '../../../../shared/components/ui/DataTable';
import type { ColumnDef } from '@tanstack/react-table';

interface AgentEditModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSave: (agent: AgentConfig) => Promise<void>;
    agent: AgentConfig | null;
}

const HISTORY_COLUMNS: ColumnDef<AgentRun, unknown>[] = [
    {
        accessorKey: 'createdAt',
        header: 'Date',
        cell: ({ getValue }) => new Date(getValue() as string).toLocaleString(),
    },
    {
        accessorKey: 'status',
        header: 'Status',
        cell: ({ getValue }) => {
            const status = getValue();
            return (
                <span className={`badge badge-sm ${status === RunStatus.COMPLETED ? 'badge-success' : status === RunStatus.FAILED ? 'badge-error' : 'badge-warning'}`}>
                    {status as string}
                </span>
            );
        },
    },
    {
        accessorKey: 'input',
        header: 'Input snippet',
        cell: ({ getValue }) => (
            <div className="truncate max-w-xs">{(getValue() as string)?.substring(0, 50) || 'N/A'}...</div>
        ),
    },
];

const AUDIT_COLUMNS: ColumnDef<AgentAudit, unknown>[] = [
    {
        accessorKey: 'createdAt',
        header: 'Date',
        cell: ({ getValue }) => <span className="whitespace-nowrap">{new Date(getValue() as string).toLocaleString()}</span>,
    },
    {
        accessorKey: 'action',
        header: 'Action',
        cell: ({ getValue }) => {
            const action = getValue() as string;
            return (
                <span className={`badge badge-sm ${action === 'CREATE' ? 'badge-success' : action === 'DELETE' ? 'badge-error' : 'badge-info'}`}>
                    {action}
                </span>
            );
        },
    },
    {
        accessorKey: 'username',
        header: 'User',
    },
    {
        accessorKey: 'changeset',
        header: 'Changes',
        enableSorting: false,
        cell: ({ getValue }) => {
            const changeset = getValue() as string;
            return (
                <div className="max-w-xs xl:max-w-md bg-base-200 p-2 rounded text-xs font-mono overflow-x-auto max-h-32">
                    <pre>{changeset !== '{}' ? JSON.stringify(JSON.parse(changeset), null, 2) : 'No changes'}</pre>
                </div>
            );
        },
    },
];

export const AgentEditModal: React.FC<AgentEditModalProps> = ({ isOpen, onClose, onSave, agent }) => {
    const { markDirty, resetDirty, guardedClose, showConfirm, confirmLeave, cancelLeave } = useUnsavedChangesGuard(onClose, isOpen);
    const [formData, setFormData] = useState<Partial<AgentConfig>>({});
    const [saving, setSaving] = useState(false);
    const [activeTab, setActiveTab] = useState<'config' | 'security' | 'operations' | 'topology' | 'history' | 'logs' | 'audit' | 'versions' | 'dx' | 'playground'>('config');
    const [historyPage, setHistoryPage] = useState<PaginatedResponse<AgentRun> | null>(null);
    const [auditPage, setAuditPage] = useState<PaginatedResponse<AgentAudit> | null>(null);
    const [topology, setTopology] = useState<AgentTopology | null>(null);
    const [logs, setLogs] = useState<string[]>([]);
    const [metrics, setMetrics] = useState<DeveloperMetrics | null>(null);
    const [versions, setVersions] = useState<AgentConfig[] | null>(null);
    const [rollingBackId, setRollingBackId] = useState<string | null>(null);
    const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
    const [loadingData, setLoadingData] = useState(false);
    
    // Playground state
    const [playgroundPrompt, setPlaygroundPrompt] = useState<string>('');
    const [playgroundResponse, setPlaygroundResponse] = useState<string>('');
    const [playgroundLoading, setPlaygroundLoading] = useState(false);

    useEffect(() => {
        if (agent) {
            setFormData(agent);
        } else {
            setFormData({
                agentId: '',
                name: '',
                description: '',
                instructions: '',
                model: 'gemini-2.5-flash',
                active: true,
                isReasoningEnabled: false,
                enforceJsonOutput: false,
                isTeam: false
            });
        }
    }, [agent, isOpen]);

    useEffect(() => {
        const fetchTabData = async () => {
            if (!isOpen) return;
            setLoadingData(true);
            try {
                if (knowledgeBases.length === 0) {
                    const kbs = await KnowledgeBasesApi.getAll();
                    setKnowledgeBases(kbs);
                }
                
                if (!agent) return;

                if (activeTab === 'history' && !historyPage) {
                    const data = await AgentAdminApi.getAgentHistory(agent.agentId);
                    setHistoryPage(data);
                } else if (activeTab === 'logs' && logs.length === 0) {
                    const data = await AgentAdminApi.getAgentLogs(agent.agentId);
                    setLogs(data);
                } else if (activeTab === 'audit' && !auditPage) {
                    const data = await AgentAdminApi.getAgentAuditHistory(agent.agentId);
                    setAuditPage(data);
                } else if (activeTab === 'topology' && !topology) {
                    const data = await AgentAdminApi.getAgentTopology(agent.agentId);
                    setTopology(data);
                } else if (activeTab === 'dx' && !metrics) {
                    const data = await AgentAdminApi.getAgentDxMetrics(agent.agentId);
                    setMetrics(data);
                } else if (activeTab === 'versions' && versions === null) {
                    const data = await AgentAdminApi.getAgentVersions(agent.agentId);
                    setVersions(data);
                }
            } catch (err) {
                console.error("Failed to load tab data", err);
            } finally {
                setLoadingData(false);
            }
        };
        fetchTabData();
    }, [activeTab, agent, isOpen]);

    if (!isOpen) return null;

    const isEdit = !!agent;

    const versionAuditId = (v: AgentConfig): string => (v as unknown as { auditId?: string }).auditId ?? v.agentId;

    const handleRollback = async (auditId: string) => {
        if (!agent) return;
        if (!window.confirm(`Roll back agent "${agent.name}" to snapshot ${auditId}? This overwrites the live configuration.`)) return;
        setRollingBackId(auditId);
        try {
            const restored = await AgentAdminApi.rollbackAgent(agent.agentId, auditId);
            setFormData(restored);
            // Force-reload the version list so the new "after rollback" snapshot appears.
            const fresh = await AgentAdminApi.getAgentVersions(agent.agentId);
            setVersions(fresh);
        } catch (err: unknown) {
            const msg = err instanceof Error ? err.message : 'Rollback failed';
            window.alert(`Rollback failed: ${msg}`);
        } finally {
            setRollingBackId(null);
        }
    };

    const versionColumns: ColumnDef<AgentConfig, unknown>[] = [
        {
            id: 'snapshot',
            header: 'Snapshot',
            cell: ({ row }) => <span className="font-mono text-xs">{versionAuditId(row.original)}</span>,
        },
        { accessorKey: 'name', header: 'Name' },
        {
            accessorKey: 'model',
            header: 'Model',
            cell: ({ getValue }) => <span className="font-mono text-xs">{getValue() as string}</span>,
        },
        {
            accessorKey: 'active',
            header: 'Active',
            cell: ({ getValue }) => {
                const active = getValue() as boolean;
                return <span className={`badge badge-sm ${active ? 'badge-success' : 'badge-ghost'}`}>{active ? 'active' : 'inactive'}</span>;
            },
        },
        {
            id: 'actions',
            header: '',
            cell: ({ row }) => {
                const auditId = versionAuditId(row.original);
                const isCurrent = rollingBackId === auditId;
                return (
                    <button
                        type="button"
                        className="btn btn-xs btn-warning"
                        disabled={isCurrent || !!rollingBackId}
                        onClick={() => handleRollback(auditId)}
                    >
                        {isCurrent ? <span className="loading loading-spinner loading-xs"></span> : 'Roll back'}
                    </button>
                );
            },
        },
    ];

    const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
        const { name, value, type } = e.target;
        
        let finalValue: any = value;
        if (type === 'checkbox') {
            finalValue = (e.target as HTMLInputElement).checked;
        } else if (type === 'select-multiple') {
            const selectOptions = (e.target as HTMLSelectElement).options;
            const selectedValues = [];
            for (let i = 0; i < selectOptions.length; i++) {
                if (selectOptions[i].selected) {
                    selectedValues.push(selectOptions[i].value);
                }
            }
            finalValue = selectedValues;
        }

        setFormData((prev: Partial<AgentConfig>) => ({ ...prev, [name]: finalValue }));
        markDirty();
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        try {
            setSaving(true);
            // Deep copy and cast for save
            await onSave(formData as AgentConfig);
            resetDirty();
            onClose();
        } catch (error) {
            console.error("Failed to save agent", error);
            alert("Failed to save agent");
        } finally {
            setSaving(false);
        }
    };

    const handleExport = async () => {
        if (!agent) return;
        try {
            const data = await AgentAdminApi.exportAgent(agent.agentId);
            const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `agent-${agent.agentId}.json`;
            a.click();
            window.URL.revokeObjectURL(url);
        } catch (error) {
            console.error("Export failed", error);
            alert("Failed to export agent configuration");
        }
    };

    const handleRunPlayground = async () => {
        if (!agent || !playgroundPrompt.trim()) return;
        setPlaygroundLoading(true);
        setPlaygroundResponse('');
        try {
            const result = await AgentsApi.runAgentPlayground(agent.agentId, playgroundPrompt);
            setPlaygroundResponse(result);
        } catch (error) {
            console.error("Playground execution failed", error);
            setPlaygroundResponse("Error: " + (error instanceof Error ? error.message : String(error)));
        } finally {
            setPlaygroundLoading(false);
        }
    };

    return (
        <dialog className="modal modal-open">
            <div className="modal-box w-11/12 max-w-3xl">
                <h3 className="font-bold text-lg mb-4">{isEdit ? `Edit Agent: ${agent?.name}` : 'Create New Agent'}</h3>
                
                {isEdit && (
                    <>
                        <div className="tabs tabs-bordered w-full mb-4">
                            <button type="button" className={`tab ${activeTab === 'config' ? 'tab-active' : ''}`} onClick={() => setActiveTab('config')}>Configuration</button>
                            <button type="button" className={`tab ${activeTab === 'security' ? 'tab-active' : ''}`} onClick={() => setActiveTab('security')}>Security & Risk</button>
                            <button type="button" className={`tab ${activeTab === 'operations' ? 'tab-active text-error font-bold' : ''}`} onClick={() => setActiveTab('operations')}>Operations</button>
                            <button type="button" className={`tab ${activeTab === 'topology' ? 'tab-active' : ''}`} onClick={() => setActiveTab('topology')}>Topology</button>
                            <button type="button" className={`tab ${activeTab === 'history' ? 'tab-active' : ''}`} onClick={() => setActiveTab('history')}>History</button>
                            <button type="button" className={`tab ${activeTab === 'logs' ? 'tab-active' : ''}`} onClick={() => setActiveTab('logs')}>Logs</button>
                            <button type="button" className={`tab ${activeTab === 'audit' ? 'tab-active' : ''}`} onClick={() => setActiveTab('audit')}>Audit Trail</button>
                            <button type="button" className={`tab ${activeTab === 'versions' ? 'tab-active' : ''}`} onClick={() => setActiveTab('versions')}>Versions</button>
                        </div>
                        <div className="tabs tabs-bordered w-full mb-4 opacity-80 pt-2 border-t border-base-300">
                            <button type="button" className={`tab ${activeTab === 'dx' ? 'tab-active font-bold text-info' : ''}`} onClick={() => setActiveTab('dx')}>Dev Experience & Metadata</button>
                            <button type="button" className={`tab ${activeTab === 'playground' ? 'tab-active font-bold text-primary' : ''}`} onClick={() => setActiveTab('playground')}>Playground</button>
                        </div>
                    </>
                )}
                
                {/* Configuration and Security are part of the editable form */}
                <form onSubmit={handleSubmit} className="flex flex-col gap-4">
                
                {activeTab === 'config' && (
                <div className="flex flex-col gap-4">
                    <div className="form-control">
                        <label className="label"><span className="label-text">Agent ID (Unique Identifier)</span></label>
                        <input type="text" name="agentId" value={formData.agentId || ''} onChange={handleChange} className="input input-bordered" disabled={isEdit} required />
                    </div>
                    
                    <div className="form-control">
                        <label className="label"><span className="label-text">Name</span></label>
                        <input type="text" name="name" value={formData.name || ''} onChange={handleChange} className="input input-bordered" required />
                    </div>
                    
                    <div className="form-control">
                        <label className="label">
                            <span className="label-text">Description</span>
                            <span className="label-text-alt opacity-70">Summary for UI routing and display</span>
                        </label>
                        <textarea name="description" value={formData.description || ''} onChange={handleChange} className="textarea textarea-bordered h-16" required></textarea>
                    </div>

                    <MarkdownEditor
                        label="Instructions (System Prompt)"
                        description="The strict rules defining the agent's behavior"
                        value={formData.instructions || ''}
                        onValueChange={(val) => setFormData(prev => ({ ...prev, instructions: val || '' }))}
                        height="300px"
                    />
                    
                    <div className="form-control">
                        <label className="label"><span className="label-text">Model</span></label>
                        <select name="model" value={formData.model || ''} onChange={handleChange} className="select select-bordered" required>
                            <option value="gemini-2.5-flash">Gemini 2.5 Flash</option>
                            <option value="gemini-2.5-pro">Gemini 2.5 Pro</option>
                            <option value="deepseek-r1">DeepSeek R1</option>
                            <option value="claude-3-5-sonnet-latest">Claude 3.5 Sonnet</option>
                            <option value="gpt-4o">GPT-4o</option>
                        </select>
                    </div>
                    
                    <div className="form-control flex-row items-center gap-4 mt-2">
                        <label className="cursor-pointer label flex gap-2">
                            <input type="checkbox" name="active" checked={formData.active !== false} onChange={handleChange} className="toggle toggle-success" />
                            <span className="label-text">Active</span>
                        </label>
                        
                        <label className="cursor-pointer label flex gap-2">
                            <input type="checkbox" name="isReasoningEnabled" checked={formData.isReasoningEnabled || false} onChange={handleChange} className="toggle toggle-primary" />
                            <span className="label-text">Enable Reasoning (System 2)</span>
                        </label>

                        <label className="cursor-pointer label flex gap-2">
                            <input type="checkbox" name="enforceJsonOutput" checked={formData.enforceJsonOutput || false} onChange={handleChange} className="toggle toggle-accent" />
                            <span className="label-text">Enforce JSON Output</span>
                        </label>
                        
                        <label className="cursor-pointer label flex gap-2">
                            <input type="checkbox" name="isTeam" checked={formData.isTeam || false} onChange={handleChange} className="toggle toggle-secondary" />
                            <span className="label-text">Is Team Coordinator</span>
                        </label>
                    </div>

                    <div className="divider opacity-50 mt-1 mb-1">Knowledge Context</div>

                    <div className="form-control">
                        <label className="label">
                            <span className="label-text">Context Window Size</span>
                            <span className="label-text-alt opacity-70">Token eviction threshold for the Agent's shared ephemeral memory.</span>
                        </label>
                        <input type="number" name="contextWindowSize" value={formData.contextWindowSize || ''} onChange={(e) => setFormData(prev => ({...prev, contextWindowSize: parseInt(e.target.value) || 0}))} className="input input-bordered" placeholder="4096" />
                    </div>

                    <div className="form-control flex-row items-center gap-4 mt-2 mb-2">
                        <label className="cursor-pointer label flex gap-2">
                            <input type="checkbox" name="memoryEnabled" checked={formData.memoryEnabled || false} onChange={handleChange} className="toggle toggle-primary" />
                            <span className="label-text">Semantic Memory Graph</span>
                        </label>

                        <label className="cursor-pointer label flex gap-2">
                            <input type="checkbox" name="addHistoryToMessages" checked={formData.addHistoryToMessages ?? true} onChange={handleChange} className="toggle toggle-primary" />
                            <span className="label-text">Include Conversational History</span>
                        </label>
                    </div>

                    <div className="form-control">
                        <label className="label">
                            <span className="label-text">Assigned Knowledge Bases</span>
                            <span className="label-text-alt">Constrain generic RAG queries to selective contexts</span>
                        </label>
                        <select 
                            name="knowledgeBaseIds" 
                            multiple 
                            value={formData.knowledgeBaseIds || []} 
                            onChange={handleChange} 
                            className="select select-bordered h-32"
                        >
                            {knowledgeBases.map(kb => (
                                <option key={kb.id} value={kb.id}>{kb.name}</option>
                            ))}
                        </select>
                        <label className="label">
                            <span className="label-text-alt opacity-70">Hold Cmd/Ctrl to select multiple. Leave completely empty to allow global search (if unrestricted).</span>
                        </label>
                    </div>

                </div>
                )}

                {activeTab === 'security' && (
                <div className="flex flex-col gap-4">
                    <div className="form-control">
                        <label className="label">
                            <span className="label-text font-bold">Allowed Roles (ACL)</span>
                            <span className="label-text-alt">Comma-separated (e.g. ROLE_ADMIN, ROLE_USER)</span>
                        </label>
                        <input 
                            type="text" 
                            name="allowedRoles" 
                            value={formData.allowedRoles?.join(', ') || ''} 
                            onChange={(e) => setFormData(prev => ({...prev, allowedRoles: e.target.value.split(',').map(s=>s.trim()).filter(Boolean)}))} 
                            className="input input-bordered" 
                            placeholder="ROLE_ADMIN"
                        />
                    </div>
                    
                    <div className="divider">Compliance Flags</div>

                    <div className="form-control flex-row items-center gap-4">
                        <label className="cursor-pointer label flex gap-2">
                            <input type="checkbox" name="requiresPiiRedaction" checked={formData.requiresPiiRedaction || false} onChange={handleChange} className="toggle toggle-warning" />
                            <span className="label-text">Requires PII Redaction</span>
                        </label>
                        
                        <label className="cursor-pointer label flex gap-2">
                            <input type="checkbox" name="approvedForProduction" checked={formData.approvedForProduction || false} onChange={handleChange} className="toggle toggle-success" />
                            <span className="label-text">Approved for Production</span>
                        </label>
                    </div>

                    {/* Granular PII Policy Bindings — visible when PII redaction is enabled */}
                    {formData.requiresPiiRedaction && formData.agentId && (
                        <AgentPiiPolicySelector agentId={formData.agentId} />
                    )}

                    <div className="stats shadow mt-4">
                        <div className="stat">
                            <div className="stat-title">Calculated Risk Profile</div>
                            <div className={`stat-value text-lg ${formData.approvedForProduction ? 'text-success' : 'text-warning'}`}>
                                {formData.approvedForProduction ? 'Low Risk (Approved)' : 'Moderate/High Risk (Review Required)'}
                            </div>
                            <div className="stat-desc">Based on tool access and PII settings</div>
                        </div>
                    </div>
                </div>
                )}

                {activeTab === 'operations' && (
                <div className="flex flex-col gap-4">
                    <div className="form-control mb-4 bg-error/10 p-4 border border-error rounded-box">
                        <label className="cursor-pointer label flex gap-2">
                            <input type="checkbox" name="maintenanceMode" checked={formData.maintenanceMode || false} onChange={handleChange} className="toggle toggle-error" />
                            <div>
                                <span className="label-text block font-bold text-error">Maintenance Mode</span>
                                <span className="label-text-alt text-error/80">Disables running and streaming for this agent. Returns 503 to clients.</span>
                            </div>
                        </label>
                    </div>

                    <div className="divider">Backup & Restore</div>
                    
                    <div className="flex flex-col gap-2">
                        <p className="text-sm opacity-70">Export this agent configuration as a JSON file to transfer to another environment or keep as a backup.</p>
                        <button type="button" className="btn btn-outline" onClick={handleExport}>
                            Export Configuration (JSON)
                        </button>
                    </div>
                </div>
                )}

                {(activeTab === 'config' || activeTab === 'security' || activeTab === 'operations' || activeTab === 'dx') && (
                    <div className="modal-action mt-6">
                        <button type="button" className="btn" onClick={guardedClose} disabled={saving}>Cancel</button>
                        <button type="submit" className="btn btn-primary" disabled={saving}>
                            {saving ? <span className="loading loading-spinner"></span> : 'Save Agent'}
                        </button>
                    </div>
                )}
                
                {activeTab === 'dx' && (
                <div className="flex flex-col gap-4">
                    <div className="flex justify-between items-end">
                        <div className="divider text-info flex-1 mr-4">Maintainability Metrics</div>
                        <button type="button" className="btn btn-sm btn-outline btn-info mb-2" onClick={() => window.open('/evaluations', '_blank')}>
                            Run Draft Evaluation
                        </button>
                    </div>
                    
                    {loadingData ? (
                        <div className="flex justify-center"><span className="loading loading-spinner text-info"></span></div>
                    ) : metrics ? (
                        <div className="stats shadow">
                            <div className="stat">
                                <div className="stat-title">Testability Score</div>
                                <div className={`stat-value ${metrics.testabilityScore > 80 ? 'text-success' : metrics.testabilityScore > 50 ? 'text-warning' : 'text-error'}`}>
                                    {metrics.testabilityScore}%
                                </div>
                                <div className="stat-desc">Based on evaluation coverage</div>
                            </div>
                            
                            <div className="stat">
                                <div className="stat-title">Maintainability Grade</div>
                                <div className="stat-value text-info">{metrics.maintainabilityGrade}</div>
                                <div className="stat-desc">Based on config complexity</div>
                            </div>
                            
                            <div className="stat">
                                <div className="stat-title">Evaluations</div>
                                <div className="stat-value">{metrics.evaluationCount}</div>
                                <div className="stat-desc">Total test runs recorded</div>
                            </div>
                        </div>
                    ) : (
                        <div className="alert alert-info">Metrics not available</div>
                    )}
                    
                    <div className="divider">Ownership & Support</div>
                    
                    <div className="form-control">
                        <label className="label">
                            <span className="label-text">Primary Owner</span>
                            <span className="label-text-alt">Email or Team Name</span>
                        </label>
                        <input type="text" name="primaryOwner" value={formData.primaryOwner || ''} onChange={handleChange} className="input input-bordered" placeholder="team-agents@company.com" />
                    </div>
                    
                    <div className="form-control">
                        <label className="label">
                            <span className="label-text">Support Channel</span>
                            <span className="label-text-alt">Slack channel, JIRA link, etc.</span>
                        </label>
                        <input type="text" name="supportChannel" value={formData.supportChannel || ''} onChange={handleChange} className="input input-bordered" placeholder="#agent-support" />
                    </div>
                    
                    <div className="form-control">
                        <label className="label">
                            <span className="label-text">Documentation URL (Markdown)</span>
                        </label>
                        <input type="url" name="markdownDocs" value={formData.markdownDocs || ''} onChange={handleChange} className="input input-bordered" placeholder="https://github.com/..." />
                    </div>

                    <div className="divider">Usability & Accessibility</div>

                    <div className="form-control">
                        <label className="label">
                            <span className="label-text">Supported Locales</span>
                            <span className="label-text-alt">Comma separated (e.g. en-US, es-ES)</span>
                        </label>
                        <input 
                            type="text" 
                            name="supportedLocales" 
                            value={formData.supportedLocales?.join(', ') || ''} 
                            onChange={(e) => setFormData(prev => ({...prev, supportedLocales: e.target.value.split(',').map(s=>s.trim()).filter(Boolean)}))} 
                            className="input input-bordered" 
                            placeholder="en-US"
                        />
                    </div>
                    
                    <div className="form-control">
                        <label className="label">
                            <span className="label-text">Accessibility Notes</span>
                        </label>
                        <input type="text" name="accessibilityCompatibility" value={formData.accessibilityCompatibility || ''} onChange={handleChange} className="input input-bordered" placeholder="WCAG 2.1 AA compliant" />
                    </div>
                    
                    <div className="form-control">
                        <label className="label">
                            <span className="label-text">Training Datasets</span>
                            <span className="label-text-alt">IDs of datasets used</span>
                        </label>
                        <input 
                            type="text" 
                            name="trainingDatasets" 
                            value={formData.trainingDatasets?.join(', ') || ''} 
                            onChange={(e) => setFormData(prev => ({...prev, trainingDatasets: e.target.value.split(',').map(s=>s.trim()).filter(Boolean)}))} 
                            className="input input-bordered" 
                            placeholder="ds-customer-service-v1"
                        />
                    </div>
                </div>
                )}

                {(activeTab === 'config' || activeTab === 'security' || activeTab === 'operations' || activeTab === 'dx') && (
                    <div className="modal-action mt-6">
                        <button type="button" className="btn" onClick={guardedClose} disabled={saving}>Cancel</button>
                        <button type="submit" className="btn btn-primary" disabled={saving}>
                            {saving ? <span className="loading loading-spinner"></span> : 'Save Agent'}
                        </button>
                    </div>
                )}

                </form>

                {activeTab === 'topology' && isEdit && (
                    <div className="py-4 h-96 w-full border border-base-300 rounded-box relative">
                        {loadingData ? (
                            <div className="absolute inset-0 flex items-center justify-center"><span className="loading loading-spinner"></span></div>
                        ) : topology ? (
                            <ReactFlow 
                                nodes={topology.nodes.map((n, i) => ({
                                    id: n.id,
                                    position: { x: (i % 3) * 200 + 50, y: Math.floor(i / 3) * 150 + 50 },
                                    data: { label: n.label }
                                }))}
                                edges={topology.edges.map(e => ({
                                    id: e.id,
                                    source: e.source,
                                    target: e.target
                                }))}
                                fitView
                            >
                                <Background />
                                <Controls />
                            </ReactFlow>
                        ) : (
                            <div className="absolute inset-0 flex items-center justify-center opacity-50">Topology not available</div>
                        )}
                    </div>
                )}

                {activeTab === 'history' && isEdit && (
                    <div className="py-4">
                        {loadingData ? (
                            <div className="text-center p-4"><span className="loading loading-spinner"></span></div>
                        ) : (
                            <DataTable
                                columns={HISTORY_COLUMNS}
                                data={historyPage?.content ?? []}
                                enablePagination
                                defaultPageSize={10}
                                emptyMessage="No execution history found."
                            />
                        )}
                    </div>
                )}

                {activeTab === 'logs' && isEdit && (
                    <div className="py-4">
                        {loadingData ? (
                            <div className="text-center p-4"><span className="loading loading-spinner"></span></div>
                        ) : (
                            <div className="bg-neutral text-neutral-content p-4 rounded-box font-mono text-xs overflow-y-auto max-h-96">
                                {logs.length > 0 ? logs.map((log: string, i: number) => (
                                    <div key={i}>{log}</div>
                                )) : (
                                    <div className="opacity-50">No recent logs.</div>
                                )}
                            </div>
                        )}
                    </div>
                )}

                {activeTab === 'audit' && isEdit && (
                    <div className="py-4">
                        {loadingData ? (
                            <div className="text-center p-4"><span className="loading loading-spinner"></span></div>
                        ) : (
                            <DataTable
                                columns={AUDIT_COLUMNS}
                                data={auditPage?.content ?? []}
                                enablePagination
                                defaultPageSize={10}
                                emptyMessage="No audit records found."
                            />
                        )}
                    </div>
                )}

                {activeTab === 'versions' && isEdit && (
                    <div className="py-4">
                        {loadingData ? (
                            <div className="text-center p-4"><span className="loading loading-spinner"></span></div>
                        ) : (
                            <>
                                <div className="alert alert-warning text-xs mb-3">
                                    <span><strong>Rollback is destructive.</strong> Restoring a snapshot overwrites the agent's live configuration; the in-place change is itself a new version, so you can roll forward again from the next entry below.</span>
                                </div>
                                {versions === null || versions.length === 0 ? (
                                    <div className="text-center py-6 text-sm opacity-50">No version snapshots recorded yet.</div>
                                ) : (
                                    <DataTable
                                        columns={versionColumns}
                                        data={versions}
                                        enablePagination
                                        defaultPageSize={10}
                                        emptyMessage="No version snapshots recorded yet."
                                    />
                                )}
                            </>
                        )}
                    </div>
                )}

                {activeTab === 'playground' && isEdit && (
                    <div className="py-4 flex flex-col gap-4">
                        <div className="alert alert-info shadow-lg">
                            <div>
                                <h3 className="font-bold">Isolated Execution Playground</h3>
                                <div className="text-xs">
                                    Prompts sent here bypass long-term vector ingestion and reflection storage, allowing you to test the agent's raw reasoning and prompt adherence safely.
                                </div>
                            </div>
                        </div>

                        <div className="form-control">
                            <label className="label">
                                <span className="label-text">Test Prompt</span>
                            </label>
                            <textarea 
                                className="textarea textarea-bordered h-24" 
                                placeholder="Type a message to test the agent..."
                                value={playgroundPrompt}
                                onChange={(e) => setPlaygroundPrompt(e.target.value)}
                            ></textarea>
                            <div className="flex justify-end mt-2">
                                <button 
                                    type="button" 
                                    className="btn btn-primary btn-sm"
                                    onClick={handleRunPlayground}
                                    disabled={playgroundLoading || !playgroundPrompt.trim()}
                                >
                                    {playgroundLoading ? <span className="loading loading-spinner"></span> : 'Send to Agent'}
                                </button>
                            </div>
                        </div>

                        {playgroundResponse && (
                            <div className="mt-4 p-4 bg-base-200 rounded-box border border-base-300">
                                <div className="font-bold mb-2 text-sm opacity-70">Response</div>
                                <div className="prose max-w-none text-sm whitespace-pre-wrap">
                                    {playgroundResponse}
                                </div>
                            </div>
                        )}
                    </div>
                )}
            </div>
            <form method="dialog" className="modal-backdrop">
                <button onClick={guardedClose}>close</button>
            </form>
            <Dialog
                isOpen={showConfirm}
                setIsOpen={(open) => { if (!open) cancelLeave(); }}
                title="Unsaved Changes"
                content="You have unsaved changes. Are you sure you want to leave? Changes will be lost."
                severity="warning"
                confirmLabel="Leave"
                cancelLabel="Stay"
                onConfirm={confirmLeave}
                onCancel={cancelLeave}
            />
        </dialog>
    );
};
