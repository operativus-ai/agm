import React, { useEffect, useMemo, useState } from 'react';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import type { Schedule, ScheduleRun } from '../../../shared/types/orchestration';
import type { ColumnDef } from '@tanstack/react-table';
import { RunStatus } from '../../../shared/types/enums';
import { Badge } from '../../../shared/components/ui/Badge';
import { Alert } from '../../../shared/components/ui/Alert';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { useParams, useNavigate } from 'react-router-dom';
import { LuArrowLeft, LuHistory } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

export const ScheduleHistoryPage: React.FC = () => {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const [schedule, setSchedule] = useState<Schedule | null>(null);
    const [runs, setRuns] = useState<ScheduleRun[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!id) return;
        loadData(id);
    }, [id]);

    const loadData = async (scheduleId: string) => {
        try {
            setLoading(true);
            setError(null);
            const [sched, runsPage] = await Promise.all([
                orchestrationApi.getSchedule(scheduleId),
                // F4 — /runs returns a paginated envelope; pull `.content`.
                orchestrationApi.getScheduleRuns(scheduleId).catch(() => null)
            ]);

            setSchedule(sched);
            setRuns(runsPage?.content ?? []);

        } catch (err: any) {
            setError(err.message || 'Failed to load schedule data.');
        } finally {
            setLoading(false);
        }
    };

    // ── Column Definitions ──────────────────────────────────────
    const columns = useMemo<ColumnDef<ScheduleRun, unknown>[]>(() => [
        {
            accessorKey: 'startedAt',
            header: 'Executed At',
            cell: ({ getValue }) => {
                const val = getValue() as string | undefined;
                return (
                    <span className="font-mono text-xs text-(--theme-muted) whitespace-nowrap">
                        {val ? new Date(val).toLocaleString() : 'Pending'}
                    </span>
                );
            },
        },
        {
            accessorKey: 'status',
            header: 'Status',
            cell: ({ getValue }) => {
                const status = getValue() as string;
                const variant = status === RunStatus.SUCCESS ? 'success' : 'error';
                return <Badge variant={variant} outline className="text-xs">{status}</Badge>;
            },
        },
        {
            accessorKey: 'id',
            header: 'Run ID',
            cell: ({ getValue }) => (
                <span className="font-mono text-xs text-(--theme-muted) opacity-70 truncate max-w-[220px] block">
                    {getValue() as string}
                </span>
            ),
        },
        {
            accessorKey: 'errorMessage',
            header: 'Diagnostics',
            enableSorting: false,
            cell: ({ getValue }) => {
                const msg = getValue() as string | undefined;
                if (!msg) return <span className="text-xs text-(--theme-muted) italic opacity-40">—</span>;
                return (
                    <span className="text-xs text-error truncate max-w-[280px] block" title={msg}>
                        {msg}
                    </span>
                );
            },
        },
    ], []);

    if (loading) {
        return (
            <div className="space-y-2">
                {[1, 2, 3, 4].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
            </div>
        );
    }

    if (error || !schedule) {
        return (
            <div className="space-y-4 p-8 text-center">
                <button className="btn btn-ghost gap-2" onClick={() => navigate('/schedules')}>
                    <LuArrowLeft className="w-4 h-4" /> Back to Schedules
                </button>
                <Alert severity="error" title="Error">{error || 'Schedule not found.'}</Alert>
            </div>
        );
    }

    return (
        <PageContainer variant="dashboard">
            <div className="flex items-center gap-4">
                <button
                    className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
                    onClick={() => navigate('/schedules')}
                >
                    <LuArrowLeft className="w-4 h-4" />
                </button>
                <PageHeader
                    icon={LuHistory}
                    title="Execution History"
                    subtitle={`${schedule.name} — Target: ${schedule.targetType} (${schedule.targetId})`}
                />
            </div>

            <DataTable
                columns={columns}
                data={runs}
                enablePagination
                defaultPageSize={25}
                emptyMessage="No executions recorded for this schedule."
            />
        </PageContainer>
    );
};
