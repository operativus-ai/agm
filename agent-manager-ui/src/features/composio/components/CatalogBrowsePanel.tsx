import React, { useMemo, useState } from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import { LuSearch, LuDownload } from 'react-icons/lu';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { Input } from '../../../shared/components/ui/Input';
import { Select } from '../../../shared/components/ui/Select';
import { Checkbox } from '../../../shared/components/ui/Checkbox';
import { Alert } from '../../../shared/components/ui/Alert';
import { composioAdminApi } from '../api/composioAdminApi';
import type {
  ComposioCatalogAction,
  ComposioCatalogImportResponse,
} from '../types';
import { TIER_LABELS } from '../types';

const TIER_OPTIONS = [1, 2, 3].map((t) => ({
  value: String(t),
  label: `T${t} — ${TIER_LABELS[t]}`,
}));

export const CatalogBrowsePanel: React.FC = () => {
  // ── Browse state ───────────────────────────────────────────
  const [app, setApp] = useState('');
  const [limit, setLimit] = useState('100');
  const [items, setItems] = useState<ComposioCatalogAction[]>([]);
  const [browseLoading, setBrowseLoading] = useState(false);
  const [browsed, setBrowsed] = useState(false);
  const [browseError, setBrowseError] = useState<string | null>(null);

  // ── Import state ───────────────────────────────────────────
  const [defaultTier, setDefaultTier] = useState('2');
  const [overwriteExisting, setOverwriteExisting] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importError, setImportError] = useState<string | null>(null);
  const [result, setResult] = useState<ComposioCatalogImportResponse | null>(null);

  const handleBrowse = async () => {
    setBrowseLoading(true);
    setBrowseError(null);
    try {
      const parsedLimit = limit.trim() ? Number(limit) : undefined;
      const data = await composioAdminApi.listCatalog(app, parsedLimit);
      setItems(data.items);
      setBrowsed(true);
    } catch (err) {
      setBrowseError(err instanceof Error ? err.message : 'Failed to load catalog.');
    } finally {
      setBrowseLoading(false);
    }
  };

  const handleImport = async () => {
    if (!app.trim()) {
      setImportError('Enter an app name (e.g. github, slack, notion) to import.');
      return;
    }
    if (
      !window.confirm(
        `Import all actions for "${app.trim()}" into the action allowlist` +
          (overwriteExisting ? ', overwriting existing rows' : '') +
          '? Imported actions become callable by agents.',
      )
    ) {
      return;
    }
    setImporting(true);
    setImportError(null);
    setResult(null);
    try {
      const res = await composioAdminApi.importApp({
        app: app.trim(),
        overwriteExisting,
        defaultTier: Number(defaultTier) as 1 | 2 | 3,
      });
      setResult(res);
    } catch (err) {
      setImportError(err instanceof Error ? err.message : 'Import failed.');
    } finally {
      setImporting(false);
    }
  };

  const columns = useMemo<ColumnDef<ComposioCatalogAction, unknown>[]>(() => [
    {
      accessorKey: 'name',
      header: 'Action',
      cell: ({ row }) => (
        <div className="flex flex-col">
          <span className="font-mono text-sm text-primary font-bold">{row.original.name}</span>
          {row.original.displayName && row.original.displayName !== row.original.name && (
            <span className="text-xs text-(--theme-muted)">{row.original.displayName}</span>
          )}
        </div>
      ),
    },
    {
      accessorKey: 'app',
      header: 'App',
      cell: ({ getValue }) => (
        <span className="text-xs text-(--theme-muted)">{(getValue() as string) ?? '—'}</span>
      ),
    },
    {
      accessorKey: 'description',
      header: 'Description',
      enableSorting: false,
      cell: ({ getValue }) => (
        <span className="text-xs text-(--theme-muted) line-clamp-2 max-w-md">
          {(getValue() as string) ?? '—'}
        </span>
      ),
    },
    {
      id: 'deprecated',
      header: '',
      enableSorting: false,
      cell: ({ row }) =>
        row.original.deprecated ? (
          <Badge variant="warning" outline size="sm">
            Deprecated
          </Badge>
        ) : null,
    },
  ], []);

  return (
    <div className="space-y-6">
      {/* ── Filter / browse ─────────────────────────────────── */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="grow min-w-48">
          <Input
            label="App filter (optional)"
            value={app}
            onChange={(e) => setApp(e.target.value)}
            placeholder="github, slack, notion… (blank = all)"
            onKeyDown={(e) => { if (e.key === 'Enter') void handleBrowse(); }}
          />
        </div>
        <div className="w-28">
          <Input
            label="Limit"
            type="number"
            value={limit}
            onChange={(e) => setLimit(e.target.value)}
            placeholder="100"
          />
        </div>
        <Button onClick={handleBrowse} disabled={browseLoading} className="gap-1.5">
          <LuSearch className="w-3.5 h-3.5" />
          {browseLoading ? 'Browsing…' : 'Browse'}
        </Button>
      </div>

      {browseError && <Alert severity="error">{browseError}</Alert>}

      {browsed && !browseError && (
        <>
          <p className="text-xs text-(--theme-muted)">
            {items.length} action{items.length === 1 ? '' : 's'} returned. An empty result usually
            means the Composio API key is unset or upstream is unreachable — check the server logs.
          </p>
          <DataTable
            columns={columns}
            data={items}
            enablePagination
            defaultPageSize={25}
            compact
            emptyMessage={browseLoading ? 'Loading…' : 'No catalog actions returned.'}
          />
        </>
      )}

      {/* ── Bulk import ─────────────────────────────────────── */}
      <div className="border-t border-(--theme-muted)/10 pt-5 space-y-4">
        <div>
          <h4 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted)">
            Bulk Import App
          </h4>
          <p className="text-xs text-(--theme-muted) mt-1">
            Imports every action under the <span className="font-mono">App filter</span> above into
            the action allowlist at the chosen default tier. Re-runs are safe — existing rows are
            skipped unless “Overwrite existing” is on.
          </p>
        </div>

        <div className="flex flex-wrap items-end gap-3">
          <div className="w-56">
            <Select
              label="Default tier"
              options={TIER_OPTIONS}
              value={defaultTier}
              onValueChange={setDefaultTier}
            />
          </div>
          <div className="pb-2">
            <Checkbox
              label="Overwrite existing (re-enable operator-disabled rows)"
              checked={overwriteExisting}
              onChange={(e) => setOverwriteExisting(e.target.checked)}
            />
          </div>
          <Button onClick={handleImport} disabled={importing} className="gap-1.5">
            <LuDownload className="w-3.5 h-3.5" />
            {importing ? 'Importing…' : 'Import App'}
          </Button>
        </div>

        {importError && <Alert severity="error">{importError}</Alert>}

        {result && (
          <Alert severity={result.failures.length > 0 ? 'warning' : 'success'}>
            <div className="space-y-1">
              <p className="font-semibold">
                Imported “{result.app}” — {result.totalFetched} fetched · {result.created.length}{' '}
                created · {result.skippedExisting.length} skipped · {result.failures.length} failed
              </p>
              {result.failures.length > 0 && (
                <ul className="text-xs list-disc ml-5 mt-1">
                  {result.failures.map((f) => (
                    <li key={f.actionName}>
                      <span className="font-mono">{f.actionName}</span>: {f.reason}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </Alert>
        )}
      </div>
    </div>
  );
};
