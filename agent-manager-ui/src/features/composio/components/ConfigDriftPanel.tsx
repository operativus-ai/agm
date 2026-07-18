import React from 'react';
import { LuRefreshCw, LuTriangleAlert, LuCircleCheck, LuCircleX, LuClock } from 'react-icons/lu';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import type { ConfigDriftResponse } from '../types';

interface ConfigDriftPanelProps {
  drift: ConfigDriftResponse | null;
  loading: boolean;
  onRefresh: () => void;
}

const SectionTitle: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <h3 className="text-sm font-semibold text-obsidian-text-secondary uppercase tracking-wide mb-2">
    {children}
  </h3>
);

const EmptyList: React.FC<{ label: string }> = ({ label }) => (
  <span className="text-xs text-obsidian-text-muted italic">{label}</span>
);

const ActionNameList: React.FC<{ names: string[]; variant: 'success' | 'warning' | 'error' | 'neutral' }> = ({ names, variant }) => {
  if (names.length === 0) return <EmptyList label="None" />;
  return (
    <div className="flex flex-wrap gap-1">
      {names.map((n) => (
        <Badge key={n} variant={variant} className="font-mono text-xs">{n}</Badge>
      ))}
    </div>
  );
};

export const ConfigDriftPanel: React.FC<ConfigDriftPanelProps> = ({ drift, loading, onRefresh }) => {
  if (loading) {
    return (
      <div className="flex items-center justify-center py-16 text-obsidian-text-muted text-sm">
        Loading config drift snapshot…
      </div>
    );
  }

  if (!drift) {
    return (
      <div className="flex flex-col items-center justify-center py-16 gap-3">
        <LuTriangleAlert className="w-8 h-8 text-obsidian-warning" />
        <span className="text-obsidian-text-muted text-sm">Failed to load drift snapshot.</span>
        <Button variant="secondary" size="sm" onClick={onRefresh}>Retry</Button>
      </div>
    );
  }

  const { actionDrift, connections, orgsWithoutConnection, registrySource, registryWasTruncated, generatedAt } = drift;
  const hasDrift = actionDrift.inRegistryNotInDb.length > 0 || actionDrift.inDbDisabled.length > 0 || orgsWithoutConnection.length > 0;

  return (
    <div className="space-y-6">
      {/* Header row */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          {hasDrift ? (
            <LuTriangleAlert className="w-5 h-5 text-obsidian-warning" />
          ) : (
            <LuCircleCheck className="w-5 h-5 text-obsidian-success" />
          )}
          <span className="text-sm font-medium text-obsidian-text-primary">
            {hasDrift ? 'Drift detected' : 'No drift — all configs in sync'}
          </span>
          <Badge variant={registrySource === 'DB' ? 'success' : 'warning'}>
            Registry: {registrySource === 'DB' ? 'DB' : 'Properties fallback'}
          </Badge>
          {registryWasTruncated && (
            <Badge variant="error">Registry truncated</Badge>
          )}
        </div>
        <div className="flex items-center gap-2 text-xs text-obsidian-text-muted">
          <LuClock className="w-3.5 h-3.5" />
          <span>Generated {new Date(generatedAt).toLocaleString()}</span>
          <Button variant="ghost" size="sm" onClick={onRefresh} className="ml-1">
            <LuRefreshCw className="w-3.5 h-3.5" />
          </Button>
        </div>
      </div>

      {/* Action drift stats */}
      <div className="grid grid-cols-3 gap-4">
        {[
          { label: 'Total in DB', value: actionDrift.totalInDb },
          { label: 'Enabled in DB', value: actionDrift.enabledInDb },
          { label: 'In live registry', value: actionDrift.inLiveRegistry },
        ].map(({ label, value }) => (
          <div key={label} className="bg-obsidian-surface rounded-lg p-4 text-center">
            <div className="text-2xl font-bold text-obsidian-text-primary">{value}</div>
            <div className="text-xs text-obsidian-text-muted mt-1">{label}</div>
          </div>
        ))}
      </div>

      {/* In registry but not in DB (properties not migrated) */}
      <div className="bg-obsidian-surface rounded-lg p-4 space-y-2">
        <div className="flex items-center gap-2">
          <LuTriangleAlert className="w-4 h-4 text-obsidian-warning" />
          <SectionTitle>In registry — not in DB ({actionDrift.inRegistryNotInDb.length})</SectionTitle>
        </div>
        <p className="text-xs text-obsidian-text-muted mb-2">
          Actions sourced from properties fallback that have not been migrated to the DB config table.
        </p>
        <ActionNameList names={actionDrift.inRegistryNotInDb} variant="warning" />
      </div>

      {/* Disabled in DB */}
      <div className="bg-obsidian-surface rounded-lg p-4 space-y-2">
        <div className="flex items-center gap-2">
          <LuCircleX className="w-4 h-4 text-obsidian-text-muted" />
          <SectionTitle>Disabled in DB ({actionDrift.inDbDisabled.length})</SectionTitle>
        </div>
        <ActionNameList names={actionDrift.inDbDisabled} variant="neutral" />
      </div>

      {/* In sync */}
      <div className="bg-obsidian-surface rounded-lg p-4 space-y-2">
        <div className="flex items-center gap-2">
          <LuCircleCheck className="w-4 h-4 text-obsidian-success" />
          <SectionTitle>In sync ({actionDrift.inSync.length})</SectionTitle>
        </div>
        <ActionNameList names={actionDrift.inSync} variant="success" />
      </div>

      {/* Connection coverage */}
      <div className="bg-obsidian-surface rounded-lg p-4 space-y-3">
        <SectionTitle>Connection coverage ({connections.length} org{connections.length !== 1 ? 's' : ''} connected)</SectionTitle>
        {connections.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-obsidian-border">
                  <th className="text-left py-1.5 pr-4 text-obsidian-text-muted font-medium">Org ID</th>
                  <th className="text-left py-1.5 pr-4 text-obsidian-text-muted font-medium">Connection ID</th>
                  <th className="text-left py-1.5 text-obsidian-text-muted font-medium">Last updated</th>
                </tr>
              </thead>
              <tbody>
                {connections.map((c) => (
                  <tr key={c.orgId} className="border-b border-obsidian-border/50 last:border-0">
                    <td className="py-1.5 pr-4 font-mono text-obsidian-text-primary">{c.orgId}</td>
                    <td className="py-1.5 pr-4 font-mono text-obsidian-text-secondary">{c.connectionId}</td>
                    <td className="py-1.5 text-obsidian-text-muted">{new Date(c.updatedAt).toLocaleDateString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyList label="No orgs have configured a Composio connection yet." />
        )}
      </div>

      {/* Orgs without connection */}
      {orgsWithoutConnection.length > 0 && (
        <div className="bg-obsidian-surface rounded-lg p-4 space-y-2 border border-obsidian-warning/30">
          <div className="flex items-center gap-2">
            <LuTriangleAlert className="w-4 h-4 text-obsidian-warning" />
            <SectionTitle>Orgs with agents but no connection ({orgsWithoutConnection.length})</SectionTitle>
          </div>
          <p className="text-xs text-obsidian-text-muted mb-2">
            These orgs have at least one agent but no Composio connection configured — Composio tools will fail at runtime.
          </p>
          <div className="flex flex-wrap gap-1">
            {orgsWithoutConnection.map((orgId) => (
              <Badge key={orgId} variant="error" className="font-mono text-xs">{orgId}</Badge>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
