import type { ColumnDef } from '@tanstack/react-table';
import { Button } from '../../../shared/components/ui/Button';
import { Badge } from '../../../shared/components/ui/Badge';
import { LuTrash2, LuTag } from 'react-icons/lu';
import type { MemoryEntry } from '../api/memoryApi';

/**
 * Column factories for MemoryManagerPage's two DataTables. Extracted to keep the
 * page a thin assembler. Behavior-preserving: same cells, same handlers wired in
 * via the callbacks below.
 */

const TIER_COLORS: Record<string, string> = {
    USER_PROFILE: 'badge-info',
    USER_MEMORY: 'badge-primary',
    SESSION_CONTEXT: 'badge-secondary',
    ENTITY_MEMORY: 'badge-accent',
    LEARNED_KNOWLEDGE: 'badge-success',
};

export function createSearchColumns(
    onDelete: (content: string) => void,
): ColumnDef<{ content: string; index: number }, unknown>[] {
    return [
        {
            accessorKey: 'content',
            header: 'Content',
            cell: ({ getValue }) => (
                <span className="text-sm whitespace-pre-wrap line-clamp-3">{getValue() as string}</span>
            ),
        },
        {
            id: 'actions',
            header: '',
            enableSorting: false,
            cell: ({ row }) => (
                <div className="flex justify-end">
                    <Button
                        size="sm"
                        variant="ghost"
                        className="text-error hover:bg-error/10"
                        onClick={() => onDelete(row.original.content)}
                    >
                        <LuTrash2 className="w-3.5 h-3.5" />
                    </Button>
                </div>
            ),
        },
    ];
}

export function createTimelineColumns(
    onEditTags: (memory: MemoryEntry) => void,
): ColumnDef<MemoryEntry, unknown>[] {
    return [
        {
            accessorKey: 'memory',
            header: 'Memory',
            cell: ({ getValue }) => (
                <span className="text-sm line-clamp-3 whitespace-pre-wrap">{getValue() as string}</span>
            ),
        },
        {
            accessorKey: 'tier',
            header: 'Tier',
            cell: ({ getValue }) => {
                const tier = getValue() as string;
                const cls = TIER_COLORS[tier] ?? 'badge-ghost';
                return <span className={`badge badge-sm ${cls}`}>{tier?.replace(/_/g, ' ')}</span>;
            },
        },
        {
            accessorKey: 'topics',
            header: 'Topics',
            cell: ({ getValue }) => {
                const t = getValue() as string[] | null;
                if (!t?.length) return <span className="text-xs text-(--theme-muted)">—</span>;
                return (
                    <div className="flex flex-wrap gap-1">
                        {t.map(tag => (
                            <Badge key={tag} variant="neutral" outline className="text-xs">{tag}</Badge>
                        ))}
                    </div>
                );
            },
        },
        {
            accessorKey: 'createdAt',
            header: 'Created',
            cell: ({ getValue }) => {
                const v = getValue() as string;
                return <span className="text-xs text-(--theme-muted)">{v ? new Date(v).toLocaleString() : '—'}</span>;
            },
        },
        {
            id: 'actions',
            header: '',
            enableSorting: false,
            cell: ({ row }) => (
                <div className="flex justify-end">
                    <Button
                        size="sm"
                        variant="ghost"
                        title="Edit tags"
                        onClick={() => onEditTags(row.original)}
                    >
                        <LuTag className="w-3.5 h-3.5" />
                    </Button>
                </div>
            ),
        },
    ];
}
