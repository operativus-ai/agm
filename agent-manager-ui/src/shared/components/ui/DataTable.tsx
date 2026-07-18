import React from 'react';
import {
  useReactTable,
  getCoreRowModel,
  getSortedRowModel,
  getPaginationRowModel,
  flexRender,
  type ColumnDef,
  type SortingState,
  type PaginationState,
} from '@tanstack/react-table';
import { cn } from '../../utils/cn';
import { LuArrowUpDown, LuArrowUp, LuArrowDown, LuChevronLeft, LuChevronRight } from 'react-icons/lu';

/**
 * Generic, headless DataTable component.
 * Bridges @tanstack/react-table logic with native DaisyUI table styling
 * to guarantee strict Obsidian design compliance.
 *
 * Supports two pagination modes:
 * 1. **Client-side** (default): All data is passed in, TanStack handles paging internally.
 * 2. **Server-side** (manual): The parent controls `pageIndex`/`pageSize` and passes
 *    `totalElements` from a Spring Boot `Page<T>` response. TanStack defers all
 *    pagination math to the server.
 *
 * @template TData - The row data type.
 */

// ── Server-Side Pagination Props ──────────────────────────────
interface ServerPaginationProps {
  /** Enable server-side (manual) pagination */
  manualPagination: true;
  /** Current page index (0-based, matching Spring Boot Pageable) */
  pageIndex: number;
  /** Current page size */
  pageSize: number;
  /** Total element count from the Spring Boot `Page<T>.totalElements` */
  totalElements: number;
  /** Callback when the user changes the page */
  onPageChange: (pageIndex: number) => void;
  /** Optional callback when the user changes page size */
  onPageSizeChange?: (pageSize: number) => void;
}

interface ClientPaginationProps {
  manualPagination?: false;
  pageIndex?: never;
  pageSize?: never;
  totalElements?: never;
  onPageChange?: never;
  onPageSizeChange?: never;
}

type PaginationProps = ServerPaginationProps | ClientPaginationProps;

type DataTableProps<TData> = PaginationProps & {
  /** Column definitions from @tanstack/react-table */
  columns: ColumnDef<TData, unknown>[];
  /** The data array to render */
  data: TData[];
  /** Optional className for the outer wrapper */
  className?: string;
  /** Show the zebra-striped rows */
  striped?: boolean;
  /** Compact row sizing */
  compact?: boolean;
  /** Pin the header row on scroll */
  pinRows?: boolean;
  /** Optional empty state message */
  emptyMessage?: string;
  /** Enable client-side pagination (ignored when manualPagination is true) */
  enablePagination?: boolean;
  /** Default page size for client-side pagination */
  defaultPageSize?: number;
  /** When set, the whole row is clickable and invokes this with the row's data. Cells with their
   *  own interactive controls should stop propagation so they don't also trigger the row click. */
  onRowClick?: (row: TData) => void;
};

export function DataTable<TData>(props: DataTableProps<TData>) {
  const {
    columns,
    data,
    className,
    striped = true,
    compact = true,
    pinRows = true,
    emptyMessage = 'No data available.',
    enablePagination = false,
    defaultPageSize = 20,
    onRowClick,
  } = props;

  const isManual = props.manualPagination === true;

  const [sorting, setSorting] = React.useState<SortingState>([]);
  const [clientPagination, setClientPagination] = React.useState<PaginationState>({
    pageIndex: 0,
    pageSize: defaultPageSize,
  });

  // Build the pagination state object for TanStack
  const paginationState: PaginationState | undefined = isManual
    ? { pageIndex: props.pageIndex, pageSize: props.pageSize }
    : enablePagination
      ? clientPagination
      : undefined;

  const pageCount = isManual
    ? Math.ceil(props.totalElements / props.pageSize)
    : undefined;

  const table = useReactTable({
    data,
    columns,
    state: {
      sorting,
      ...(paginationState ? { pagination: paginationState } : {}),
    },
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    // Pagination models
    ...(isManual
      ? {
          manualPagination: true,
          pageCount,
          onPaginationChange: (updater) => {
            const next = typeof updater === 'function' ? updater(paginationState!) : updater;
            props.onPageChange(next.pageIndex);
            if (props.onPageSizeChange && next.pageSize !== paginationState!.pageSize) {
              props.onPageSizeChange(next.pageSize);
            }
          },
        }
      : enablePagination
        ? {
            getPaginationRowModel: getPaginationRowModel(),
            onPaginationChange: setClientPagination,
          }
        : {}),
  });

  const showPagination = isManual || enablePagination;
  const currentPage = table.getState().pagination?.pageIndex ?? 0;
  const totalPages = table.getPageCount();

  return (
    <div className="flex flex-col gap-0">
      {/* ── Table ── */}
      <div className={cn('overflow-visible rounded-lg border border-(--theme-muted)/10', className)}>
        <table
          className={cn(
            'table w-full',
            striped && 'table-zebra',
            compact && 'table-sm',
            pinRows && 'table-pin-rows',
          )}
        >
          {/* Header */}
          <thead>
            {table.getHeaderGroups().map((headerGroup) => (
              <tr key={headerGroup.id} className="bg-(--theme-card) border-b border-(--theme-muted)/10">
                {headerGroup.headers.map((header) => {
                  const canSort = header.column.getCanSort();
                  const sorted = header.column.getIsSorted();

                  return (
                    <th
                      key={header.id}
                      scope="col"
                      aria-sort={sorted === 'asc' ? 'ascending' : sorted === 'desc' ? 'descending' : undefined}
                      className={cn(
                        'text-xs font-bold tracking-wider uppercase text-(--theme-muted) select-none whitespace-nowrap',
                        canSort && 'cursor-pointer hover:text-(--theme-foreground) transition-colors',
                      )}
                      onClick={canSort ? header.column.getToggleSortingHandler() : undefined}
                    >
                      <div className="flex items-center gap-1.5">
                        {header.isPlaceholder
                          ? null
                          : flexRender(header.column.columnDef.header, header.getContext())}
                        {canSort && (
                          <span className="inline-flex" aria-hidden="true">
                            {sorted === 'asc' ? (
                              <LuArrowUp className="w-3 h-3" />
                            ) : sorted === 'desc' ? (
                              <LuArrowDown className="w-3 h-3" />
                            ) : (
                              <LuArrowUpDown className="w-3 h-3 opacity-30" />
                            )}
                          </span>
                        )}
                      </div>
                    </th>
                  );
                })}
              </tr>
            ))}
          </thead>

          {/* Body */}
          <tbody>
            {table.getRowModel().rows.length === 0 ? (
              <tr>
                <td
                  colSpan={columns.length}
                  className="text-center py-12 text-(--theme-muted) text-sm"
                >
                  {emptyMessage}
                </td>
              </tr>
            ) : (
              table.getRowModel().rows.map((row) => (
                <tr
                  key={row.id}
                  className={cn(
                    'hover:bg-(--theme-muted)/5 transition-colors border-b border-(--theme-muted)/5',
                    onRowClick && 'cursor-pointer',
                  )}
                  onClick={onRowClick ? () => onRowClick(row.original) : undefined}
                >
                  {row.getVisibleCells().map((cell) => (
                    <td key={cell.id} className="text-sm">
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* ── Pagination Footer ── */}
      {showPagination && totalPages > 0 && (
        <div className="flex items-center justify-between px-4 py-3">
          {/* Page info */}
          <span className="text-xs text-(--theme-muted)" role="status" aria-live="polite">
            Page {currentPage + 1} of {totalPages}
            {isManual && (
              <span className="ml-2 opacity-60">
                ({props.totalElements.toLocaleString()} total)
              </span>
            )}
          </span>

          {/* Controls */}
          <div className="join">
            <button
              className="join-item btn btn-sm btn-ghost"
              disabled={!table.getCanPreviousPage()}
              onClick={() => table.previousPage()}
              aria-label="Previous page"
            >
              <LuChevronLeft className="w-4 h-4" aria-hidden="true" />
            </button>

            {/* Page number buttons — show up to 5 centered on current page */}
            {(() => {
              const maxVisible = 5;
              let start = Math.max(0, currentPage - Math.floor(maxVisible / 2));
              const end = Math.min(totalPages, start + maxVisible);
              if (end - start < maxVisible) {
                start = Math.max(0, end - maxVisible);
              }

              return Array.from({ length: end - start }, (_, i) => {
                const pageIdx = start + i;
                return (
                  <button
                    key={pageIdx}
                    className={cn(
                      'join-item btn btn-sm',
                      pageIdx === currentPage ? 'btn-primary' : 'btn-ghost',
                    )}
                    onClick={() => table.setPageIndex(pageIdx)}
                  >
                    {pageIdx + 1}
                  </button>
                );
              });
            })()}

            <button
              className="join-item btn btn-sm btn-ghost"
              disabled={!table.getCanNextPage()}
              onClick={() => table.nextPage()}
              aria-label="Next page"
            >
              <LuChevronRight className="w-4 h-4" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
