import type { ColumnDef } from '@tanstack/react-table';
import type { KnowledgeDocument, KnowledgeBase } from '../../../shared/types/api';
import { RunStatus } from '../../../shared/types/enums';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import {
    LuFile, LuTrash2, LuEye, LuFolderOpen, LuArrowRight, LuRotateCcw,
} from 'react-icons/lu';
import { parseDate, formatBytes, PROCESSING_STALE_MS } from '../utils/knowledgeFormat';

interface DocumentColumnOptions {
    knowledgeBases: KnowledgeBase[];
    selectedBaseId: string | undefined;
    onOpenModal: (doc: KnowledgeDocument) => void;
    onRetry: (id: string) => void;
    onOpenMove: (doc: KnowledgeDocument) => void;
    onDelete: (id: string) => void;
}

/**
 * Column factory for the knowledge documents DataTable. Extracted from
 * KnowledgePage to keep the page a thinner assembler. Behavior-preserving:
 * same cells, the Collection column is conditional on there being no selected
 * base, and row actions wire in via the callbacks below.
 */
export function createDocumentColumns(opts: DocumentColumnOptions): ColumnDef<KnowledgeDocument, unknown>[] {
    const { knowledgeBases, selectedBaseId, onOpenModal, onRetry, onOpenMove, onDelete } = opts;

    const cols: ColumnDef<KnowledgeDocument, unknown>[] = [
        {
            accessorKey: 'name',
            header: 'Filename',
            cell: ({ row }) => {
                const doc = row.original;
                const isStale = doc.status === RunStatus.PROCESSING &&
                    Date.now() - parseDate(doc.createdAt).getTime() > PROCESSING_STALE_MS;
                return (
                    <div
                        className="flex items-center gap-3 min-w-0 cursor-pointer hover:text-primary transition-colors"
                        onClick={() => onOpenModal(doc)}
                    >
                        <LuFile className="w-4 h-4 shrink-0 text-(--theme-muted)" />
                        <div className="min-w-0">
                            <span className="font-medium truncate block max-w-64 text-info" title={doc.name}>{doc.name}</span>
                            {doc.description && (
                                <span className="text-[11px] text-(--theme-muted) truncate block max-w-64">{doc.description}</span>
                            )}
                        </div>
                        {isStale && <Badge variant="warning" outline className="text-[10px]">Stuck</Badge>}
                    </div>
                );
            },
        },
        {
            accessorKey: 'contentType',
            header: 'Type',
            cell: ({ getValue }) => {
                const ct = getValue() as string;
                const label = !ct ? 'Unknown' :
                    ct.includes('markdown') || ct.includes('html') ? 'Web' :
                    ct.includes('pdf') ? 'PDF' :
                    ct.includes('json') ? 'JSON' :
                    ct.includes('csv') ? 'CSV' :
                    ct.split('/')[1]?.toUpperCase() || 'File';
                return <Badge variant="neutral" outline className="text-xs">{label}</Badge>;
            },
        },
    ];

    if (!selectedBaseId) {
        cols.push({
            accessorKey: 'knowledgeBaseId',
            header: 'Collection',
            cell: ({ getValue }) => {
                const kbId = getValue() as string | undefined;
                const kb = knowledgeBases.find(k => k.id === kbId);
                return kb ? (
                    <span className="flex items-center gap-1.5 text-xs">
                        <LuFolderOpen className="w-3.5 h-3.5 text-(--theme-muted)" />
                        {kb.name}
                    </span>
                ) : (
                    <span className="text-xs text-(--theme-muted) opacity-50">—</span>
                );
            },
        });
    }

    cols.push(
        {
            id: 'chunks',
            header: 'Chunks',
            accessorFn: (row) => row.vectorIds?.length ?? 0,
            cell: ({ getValue }) => (
                <span className="font-mono bg-obsidian-elevated px-2 py-0.5 rounded text-xs">{getValue() as number}</span>
            ),
        },
        {
            accessorKey: 'size',
            header: 'Size',
            cell: ({ getValue }) => (
                <span className="text-xs text-(--theme-muted)">{formatBytes(getValue() as number)}</span>
            ),
        },
        {
            accessorKey: 'accessCount',
            header: 'Queries',
            cell: ({ getValue }) => (
                <span className="font-mono bg-obsidian-elevated px-2 py-0.5 rounded text-xs">{(getValue() as number) ?? 0}</span>
            ),
        },
        {
            accessorKey: 'status',
            header: 'Status',
            cell: ({ row }) => {
                const doc = row.original;
                const status = doc.status as string;
                const variant = status === RunStatus.COMPLETED ? 'success' :
                    status === RunStatus.PROCESSING ? 'info' : 'error';
                return (
                    <div className="flex flex-col gap-0.5">
                        <Badge variant={variant} outline className="text-xs">{status}</Badge>
                        {status === RunStatus.FAILED && doc.statusMessage && (
                            <span className="text-[10px] text-error opacity-70 max-w-32 truncate" title={doc.statusMessage}>
                                {doc.statusMessage}
                            </span>
                        )}
                    </div>
                );
            },
        },
        {
            accessorKey: 'createdAt',
            header: 'Uploaded',
            cell: ({ getValue }) => (
                <span className="text-xs text-(--theme-muted) whitespace-nowrap">
                    {parseDate(getValue()).toLocaleDateString()}
                </span>
            ),
        },
        {
            id: 'actions',
            header: '',
            enableSorting: false,
            cell: ({ row }) => {
                const doc = row.original;
                return (
                    <div className="flex items-center justify-end gap-1">
                        <Button
                            size="sm"
                            variant="ghost"
                            title="View Details"
                            className="px-2 text-(--theme-muted) hover:text-primary"
                            onClick={() => onOpenModal(doc)}
                        >
                            <LuEye className="w-3.5 h-3.5" />
                        </Button>
                        {doc.status === RunStatus.FAILED && (
                            <button
                                type="button"
                                onClick={() => onRetry(doc.id)}
                                aria-label="Retry ingestion"
                                title="Retry"
                                className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
                            >
                                <LuRotateCcw className="w-4 h-4" />
                            </button>
                        )}
                        <button
                            type="button"
                            onClick={() => onOpenMove(doc)}
                            aria-label="Move to collection"
                            title="Move to…"
                            className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
                        >
                            <LuArrowRight className="w-4 h-4" />
                        </button>
                        <button
                            type="button"
                            onClick={() => onDelete(doc.id)}
                            aria-label="Delete document"
                            title="Delete"
                            className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-error"
                        >
                            <LuTrash2 className="w-4 h-4" />
                        </button>
                    </div>
                );
            },
        }
    );

    return cols;
}
