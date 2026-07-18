import React, { useEffect, useMemo, useState } from 'react';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import type { Approval } from '../../../shared/types/orchestration';
import type { ColumnDef } from '@tanstack/react-table';
import { RunStatus } from '../../../shared/types/enums';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { logger } from '../../../utils/logger';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Tabs } from '../../../shared/components/ui/Tabs';
import { ApprovalReviewModal } from '../components/ApprovalReviewModal';
import { LuShieldCheck, LuEye, LuCheckCheck, LuX, LuZap } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { APPROVALS_TABS, mergeApprovalsTabs } from '../approvalsTabs';
import { useLicense } from '../../../shared/license/useLicense';

const getAgeBadge = (createdAt: string) => {
    const hours = (Date.now() - new Date(createdAt).getTime()) / 3_600_000;
    if (hours >= 20) return { label: `${Math.floor(hours)}h`, cls: 'text-error' };
    if (hours >= 8)  return { label: `${Math.floor(hours)}h`, cls: 'text-warning' };
    const mins = Math.floor(hours * 60);
    return { label: mins < 60 ? `${mins}m` : `${Math.floor(hours)}h`, cls: 'text-(--theme-muted)' };
};

export const ApprovalsPage: React.FC = () => {
    const [approvals, setApprovals] = useState<Approval[]>([]);
    const [loading, setLoading] = useState(true);
    const [activeTab, setActiveTab] = useState<string>('PENDING');
    const { license } = useLicense();
    // Data-level tab registry: edition tabs (e.g. Escalations) merge in here once the EE
    // feature exists; featureKey-gated tabs hide without the license.
    const tabs = mergeApprovalsTabs(APPROVALS_TABS, [])
        .filter(t => !t.featureKey || license.features.includes(t.featureKey));
    const activeDef = tabs.find(t => t.value === activeTab);
    const [selectedApproval, setSelectedApproval] = useState<Approval | null>(null);
    const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
    const [bulkLoading, setBulkLoading] = useState(false);

    const PAGE_SIZE = 20;
    const [pageIndex, setPageIndex] = useState(0);
    const [totalElements, setTotalElements] = useState(0);

    useEffect(() => {
        loadApprovals(activeTab);
    }, [activeTab, pageIndex]);

    const loadApprovals = async (status: string) => {
        if (status !== 'PENDING' && status !== 'RESOLVED') {
            // Self-contained tabs (registry `render`) manage their own data.
            setLoading(false);
            return;
        }
        try {
            setLoading(true);
            setSelectedIds(new Set());
            const statusParam = status === 'RESOLVED' ? `${RunStatus.APPROVED},${RunStatus.REJECTED}` : RunStatus.PENDING;
            const data = await orchestrationApi.getApprovals({ status: statusParam, page: pageIndex, size: PAGE_SIZE });
            if (data && data.content) {
                setApprovals(data.content);
                setTotalElements(data.page.totalElements);
            } else {
                setApprovals([]);
                setTotalElements(0);
            }
        } catch (err) {
            logger.error("API Fetch failed", err);
        } finally {
            setLoading(false);
        }
    };

    const handleResolve = (approval: Approval) => setSelectedApproval(approval);

    const handleModalClose = () => {
        setSelectedApproval(null);
        loadApprovals(activeTab);
    };

    const handleTabChange = (value: string) => {
        setActiveTab(value);
        setPageIndex(0);
    };

    const allPageIds = approvals.filter(a => a.status === RunStatus.PENDING).map(a => a.id);
    const allSelected = allPageIds.length > 0 && allPageIds.every(id => selectedIds.has(id));

    const toggleSelectAll = () => {
        if (allSelected) {
            setSelectedIds(prev => { const next = new Set(prev); allPageIds.forEach(id => next.delete(id)); return next; });
        } else {
            setSelectedIds(prev => new Set([...prev, ...allPageIds]));
        }
    };

    const toggleSelectOne = (id: string) => {
        setSelectedIds(prev => {
            const next = new Set(prev);
            next.has(id) ? next.delete(id) : next.add(id);
            return next;
        });
    };

    const handleBulkResolve = async (decision: 'APPROVED' | 'REJECTED') => {
        if (selectedIds.size === 0) return;
        if (!confirm(`${decision === 'APPROVED' ? 'Approve' : 'Reject'} ${selectedIds.size} approval(s)?`)) return;
        try {
            setBulkLoading(true);
            await orchestrationApi.bulkResolveApprovals([...selectedIds], decision);
            setSelectedIds(new Set());
            await loadApprovals(activeTab);
        } catch (err) {
            logger.error('Bulk resolve failed', err);
        } finally {
            setBulkLoading(false);
        }
    };

    // ── Column Definitions ──────────────────────────────────────
    const columns = useMemo<ColumnDef<Approval, unknown>[]>(() => [
        ...(activeTab === 'PENDING' ? [{
            id: 'select',
            header: () => (
                <input
                    type="checkbox"
                    className="checkbox checkbox-xs"
                    checked={allSelected}
                    onChange={toggleSelectAll}
                />
            ),
            enableSorting: false,
            cell: ({ row }: { row: { original: Approval } }) => (
                row.original.status === RunStatus.PENDING ? (
                    <input
                        type="checkbox"
                        className="checkbox checkbox-xs"
                        checked={selectedIds.has(row.original.id)}
                        onChange={() => toggleSelectOne(row.original.id)}
                        onClick={e => e.stopPropagation()}
                    />
                ) : null
            ),
        } as ColumnDef<Approval, unknown>] : []),
        {
            accessorKey: 'createdAt',
            header: 'Requested',
            cell: ({ getValue }) => {
                const val = getValue() as string;
                const age = getAgeBadge(val);
                return (
                    <div className="flex items-center gap-2">
                        <span className="font-mono text-xs text-(--theme-muted) whitespace-nowrap">
                            {new Date(val).toLocaleString()}
                        </span>
                        <span className={`text-xs font-mono font-bold ${age.cls}`}>{age.label}</span>
                    </div>
                );
            },
        },
        {
            accessorKey: 'agentId',
            header: 'Agent',
            cell: ({ row }) => {
                const app = row.original;
                return (
                    <div className="min-w-0">
                        <div className="font-medium text-sm truncate max-w-40" title={app.agentId}>{app.agentId}</div>
                        <div className="text-xs text-(--theme-muted) font-mono truncate max-w-40">
                            Run: {app.runId.substring(0, 8)}…
                        </div>
                    </div>
                );
            },
        },
        {
            accessorKey: 'toolName',
            header: 'Action',
            cell: ({ getValue }) => {
                const name = getValue() as string;
                const isComposio = name?.toLowerCase().startsWith('composio');
                return (
                    <div className="flex items-center gap-1.5">
                        <Badge variant="neutral" outline className="text-xs font-mono">
                            {name}
                        </Badge>
                        {isComposio && (
                            <Badge variant="warning" outline className="text-xs gap-1 flex items-center">
                                <LuZap className="w-3 h-3" /> Composio
                            </Badge>
                        )}
                    </div>
                );
            },
        },
        {
            accessorKey: 'status',
            header: 'Status',
            cell: ({ getValue }) => {
                const status = getValue() as string;
                const variant = status === RunStatus.PENDING ? 'warning'
                    : status === RunStatus.APPROVED ? 'success' : 'error';
                return <Badge variant={variant} outline className="text-xs">{status}</Badge>;
            },
        },
        {
            id: 'resolution',
            header: activeTab === 'PENDING' ? 'Actions' : 'Resolved',
            enableSorting: false,
            cell: ({ row }) => {
                const app = row.original;
                if (app.status === RunStatus.PENDING) {
                    return (
                        <Button size="sm" className="gap-1.5" onClick={() => handleResolve(app)}>
                            <LuShieldCheck className="w-3.5 h-3.5" /> Review
                        </Button>
                    );
                }
                return (
                    <div className="text-xs text-(--theme-muted)">
                        <span>{app.resolvedAt ? new Date(app.resolvedAt).toLocaleDateString() : '—'}</span>
                        {app.resolvedBy && <span className="ml-1.5 opacity-60">by {app.resolvedBy}</span>}
                    </div>
                );
            },
        },
        {
            id: 'actions',
            header: '',
            enableSorting: false,
            cell: ({ row }) => (
                <div className="flex items-center justify-end gap-1">
                    <button
                        type="button"
                        onClick={() => handleResolve(row.original)}
                        aria-label="View payload"
                        title="View Payload"
                        className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
                    >
                        <LuEye className="w-4 h-4" />
                    </button>
                </div>
            ),
        },
    ], [activeTab, selectedIds, allSelected]);

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuShieldCheck}
                title="Human-in-the-Loop Approvals"
                subtitle="Review and authorize restricted actions requested by autonomous agents."
            />

            <Tabs defaultValue="PENDING" onValueChange={handleTabChange}>
                <Tabs.List className="max-w-fit">
                    {tabs.map(t => (
                        <Tabs.Trigger key={t.value} value={t.value}>{t.label}</Tabs.Trigger>
                    ))}
                </Tabs.List>

                <Tabs.Content value={activeTab}>
                    {activeDef?.render ? (
                        activeDef.render()
                    ) : (
                    <>
                    {selectedIds.size > 0 && (
                        <div className="flex items-center gap-2 px-3 py-2 mb-3 bg-obsidian-elevated border border-obsidian-stroke rounded-md">
                            <span className="text-xs text-(--theme-muted) font-mono mr-1">{selectedIds.size} selected</span>
                            <Button size="sm" variant="ghost" className="gap-1.5 text-success hover:bg-success/10" disabled={bulkLoading} onClick={() => handleBulkResolve('APPROVED')}>
                                <LuCheckCheck className="w-3.5 h-3.5" /> Approve All
                            </Button>
                            <Button size="sm" variant="ghost" className="gap-1.5 text-error hover:bg-error/10" disabled={bulkLoading} onClick={() => handleBulkResolve('REJECTED')}>
                                <LuX className="w-3.5 h-3.5" /> Reject All
                            </Button>
                        </div>
                    )}
                    {loading ? (
                        <div className="space-y-2">
                            {[1, 2, 3, 4].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
                        </div>
                    ) : (
                        <DataTable
                            columns={columns}
                            data={approvals}
                            manualPagination
                            pageIndex={pageIndex}
                            pageSize={PAGE_SIZE}
                            totalElements={totalElements}
                            onPageChange={setPageIndex}
                            emptyMessage={`No ${activeTab.toLowerCase()} approvals found.`}
                        />
                    )}
                    </>
                    )}
                </Tabs.Content>
            </Tabs>

            {selectedApproval && (
                <ApprovalReviewModal approval={selectedApproval} onClose={handleModalClose} />
            )}
        </PageContainer>
    );
};
