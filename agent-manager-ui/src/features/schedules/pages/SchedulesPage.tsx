import React, { useEffect, useMemo, useState } from 'react';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import type { Schedule, SpotBatchJob } from '../../../shared/types/orchestration';
import type { ColumnDef } from '@tanstack/react-table';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { Badge } from '../../../shared/components/ui/Badge';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Alert } from '../../../shared/components/ui/Alert';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { useNavigate } from 'react-router-dom';
import { ScheduleFormModal } from '../components/ScheduleFormModal';
import {
    LuCalendar, LuPlay, LuHistory, LuPencil, LuTrash2, LuPlus, LuCpu,
} from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { RunStatus } from '../../../shared/types/enums';

export const SchedulesPage: React.FC = () => {
    const [schedules, setSchedules] = useState<Schedule[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [batches, setBatches] = useState<SpotBatchJob[]>([]);
    const [deleteTarget, setDeleteTarget] = useState<Schedule | null>(null);
    const [deleteError, setDeleteError] = useState<string | null>(null);
    const [deleting, setDeleting] = useState(false);
    const navigate = useNavigate();

    // Pagination state
    const PAGE_SIZE = 20;
    const [pageIndex, setPageIndex] = useState(0);
    const [totalElements, setTotalElements] = useState(0);

    useEffect(() => {
        loadSchedules();
        loadBatches();
    }, [pageIndex]);

    const loadSchedules = async () => {
        try {
            setLoading(true);
            setError(null);
            const data = await orchestrationApi.getSchedules({ page: pageIndex, size: PAGE_SIZE });
            if (data && data.content) {
                setSchedules(data.content);
                setTotalElements(data.page.totalElements);
            } else {
                setSchedules([]);
                setTotalElements(0);
            }
        } catch (err: any) {
            console.error("API Fetch failed", err);
            setError(err.message || 'Failed to load schedules.');
        } finally {
            setLoading(false);
        }
    };

    const loadBatches = async () => {
        try {
            const data = await orchestrationApi.getScheduleBatches();
            setBatches(data || []);
        } catch {
            setBatches([]);
        }
    };

    const handleToggleActive = async (id: string, currentStatus: boolean) => {
        try {
            await orchestrationApi.updateSchedule(id, { isActive: !currentStatus });
            loadSchedules();
        } catch (err) {
            console.error("Failed to toggle schedule", err);
        }
    };

    const handleTrigger = async (id: string) => {
        try {
            await orchestrationApi.triggerSchedule(id);
            setError(null);
            loadSchedules();
        } catch (err: any) {
            console.error("Failed to trigger schedule", err);
            setError(err.message || "Failed to trigger schedule manually.");
        }
    };

    const handleConfirmDelete = async () => {
        if (!deleteTarget) return;
        setDeleting(true);
        setDeleteError(null);
        try {
            await orchestrationApi.deleteSchedule(deleteTarget.id);
            setDeleteTarget(null);
            loadSchedules();
        } catch (err: any) {
            console.error("Failed to delete schedule", err);
            setDeleteError(err?.message || "Failed to delete schedule.");
        } finally {
            setDeleting(false);
        }
    };

    // ── Column Definitions ──────────────────────────────────────
    const columns = useMemo<ColumnDef<Schedule, unknown>[]>(() => [
        {
            id: 'status',
            header: 'Active',
            accessorKey: 'isActive',
            cell: ({ row }) => {
                const sched = row.original;
                return (
                    <input
                        type="checkbox"
                        className="toggle toggle-success toggle-sm"
                        checked={sched.isActive}
                        onChange={() => handleToggleActive(sched.id, sched.isActive)}
                    />
                );
            },
        },
        {
            accessorKey: 'name',
            header: 'Name',
            cell: ({ row }) => {
                const sched = row.original;
                return (
                    <div className="min-w-0">
                        <div className="font-medium text-sm">{sched.name}</div>
                        {sched.description && (
                            <div className="text-xs text-(--theme-muted) truncate max-w-50">{sched.description}</div>
                        )}
                    </div>
                );
            },
        },
        {
            accessorKey: 'targetId',
            header: 'Target',
            cell: ({ row }) => {
                const sched = row.original;
                return (
                    <div className="min-w-0">
                        <div className="font-mono text-xs truncate max-w-35" title={sched.targetId}>{sched.targetId}</div>
                        <Badge variant="neutral" outline className="text-[10px] mt-0.5">{sched.targetType}</Badge>
                    </div>
                );
            },
        },
        {
            accessorKey: 'cronExpression',
            header: 'CRON',
            cell: ({ getValue }) => (
                <span className="font-mono bg-obsidian-elevated px-2 py-0.5 rounded text-xs">{getValue() as string}</span>
            ),
        },
        {
            id: 'nextRun',
            header: 'Next Run',
            accessorFn: (row) => row.nextRunAt,
            cell: ({ row }) => {
                const sched = row.original;
                if (sched.isActive && sched.nextRunAt) {
                    return (
                        <span className="text-xs text-(--theme-muted) whitespace-nowrap">
                            {new Date(sched.nextRunAt).toLocaleString()}
                        </span>
                    );
                }
                return <span className="text-xs text-(--theme-muted) italic opacity-50">Not scheduled</span>;
            },
        },
        {
            id: 'history',
            header: 'History',
            enableSorting: false,
            cell: ({ row }) => (
                <Button
                    size="sm"
                    variant="ghost"
                    className="gap-1 text-(--theme-muted) hover:text-primary"
                    onClick={() => navigate(`/schedules/${row.original.id}/history`)}
                >
                    <LuHistory className="w-3 h-3" /> Runs
                </Button>
            ),
        },
        {
            id: 'actions',
            header: '',
            enableSorting: false,
            cell: ({ row }) => {
                const sched = row.original;
                return (
                    <div className="flex items-center justify-end gap-1">
                        <button
                            type="button"
                            onClick={() => setIsModalOpen(true)}
                            aria-label="Edit schedule"
                            title="Edit"
                            className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
                        >
                            <LuPencil className="w-4 h-4" />
                        </button>
                        <button
                            type="button"
                            onClick={() => handleTrigger(sched.id)}
                            aria-label="Run schedule now"
                            title="Run Now"
                            className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-info"
                        >
                            <LuPlay className="w-4 h-4" />
                        </button>
                        <button
                            type="button"
                            onClick={() => { setDeleteError(null); setDeleteTarget(sched); }}
                            aria-label="Delete schedule"
                            title="Delete"
                            className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-error"
                        >
                            <LuTrash2 className="w-4 h-4" />
                        </button>
                    </div>
                );
            },
        },
    ], []);

    const batchStatusVariant = (status: string) => {
        if (status === RunStatus.COMPLETED) return 'success';
        if (status === RunStatus.FAILED) return 'error';
        if (status === RunStatus.RUNNING) return 'info';
        return 'neutral';
    };

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuCalendar}
                title="Schedules"
                subtitle="Configure autonomous CRON triggers for agents, teams, and workflows."
                actions={
                    <Button size="sm" onClick={() => setIsModalOpen(true)} className="gap-1.5">
                        <LuPlus className="w-4 h-4" /> Create Schedule
                    </Button>
                }
            />

            {error && <Alert severity="error" title="Error">{error}</Alert>}

            {loading ? (
                <div className="space-y-2">
                    {[1, 2, 3, 4].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
                </div>
            ) : (
                <DataTable
                    columns={columns}
                    data={schedules}
                    manualPagination
                    pageIndex={pageIndex}
                    pageSize={PAGE_SIZE}
                    totalElements={totalElements}
                    onPageChange={setPageIndex}
                    emptyMessage="No schedules defined. Create one to automate agent executions."
                />
            )}

            {/* Spot Batch Jobs */}
            {batches.length > 0 && (
                <details className="group mt-2" open>
                    <summary className="flex items-center gap-2 cursor-pointer list-none py-2 px-1 text-sm font-medium text-(--theme-muted) hover:text-(--theme-foreground) transition-colors">
                        <LuCpu className="w-4 h-4" />
                        Spot Batch Jobs
                        <Badge variant="neutral" className="text-[10px] ml-1">{batches.length}</Badge>
                        <svg className="w-3 h-3 ml-auto transition-transform group-open:rotate-180" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <polyline points="6 9 12 15 18 9" />
                        </svg>
                    </summary>
                    <div className="mt-3 overflow-x-auto rounded-lg border border-(--theme-muted)/10 bg-(--theme-card)">
                        <table className="table table-sm w-full">
                            <thead>
                                <tr className="text-(--theme-muted) text-xs uppercase tracking-wide border-b border-(--theme-muted)/10">
                                    <th>Job</th>
                                    <th>Compute</th>
                                    <th>Progress</th>
                                    <th>Cost</th>
                                    <th>Status</th>
                                </tr>
                            </thead>
                            <tbody>
                                {batches.map(b => (
                                    <tr key={b.id} className="border-b border-(--theme-muted)/5 hover:bg-(--theme-muted)/5">
                                        <td className="font-medium text-sm">{b.job}</td>
                                        <td className="font-mono text-xs text-(--theme-muted)">{b.compute}</td>
                                        <td>
                                            <div className="flex items-center gap-2 min-w-24">
                                                <progress className="progress progress-info w-16 h-1.5" value={b.progress} max="100" />
                                                <span className="text-xs text-(--theme-muted)">{b.progress}%</span>
                                            </div>
                                        </td>
                                        <td className="font-mono text-xs">${b.cost.toFixed(4)}</td>
                                        <td><Badge variant={batchStatusVariant(b.status)} outline className="text-xs">{b.status}</Badge></td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </details>
            )}

            {isModalOpen && (
                <ScheduleFormModal onClose={() => { setIsModalOpen(false); loadSchedules(); }} />
            )}

            <Dialog
                isOpen={deleteTarget !== null}
                setIsOpen={(open) => {
                    if (!open) {
                        setDeleteTarget(null);
                        setDeleteError(null);
                    }
                }}
                title="Delete schedule"
                content={
                    deleteError
                        ? `Failed to delete "${deleteTarget?.name}": ${deleteError}`
                        : `Delete schedule "${deleteTarget?.name}"? Any future runs will stop firing. In-flight runs are unaffected.`
                }
                severity="error"
                confirmLabel={deleting ? 'Deleting…' : 'Delete'}
                cancelLabel="Cancel"
                shouldCloseOnConfirm={false}
                onConfirm={handleConfirmDelete}
                onCancel={() => {
                    setDeleteTarget(null);
                    setDeleteError(null);
                }}
            />
        </PageContainer>
    );
};
