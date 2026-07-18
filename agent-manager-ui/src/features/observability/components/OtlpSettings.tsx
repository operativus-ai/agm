import React, { useState, useEffect } from 'react';
import { ApiClient } from '../../../shared/api/client';
import { Typography } from '../../../shared/components/ui/Typography';

interface OtlpConfig {
  enabled: boolean;
  endpoint: string;
  includePrompts: boolean;
  batchSize: number;
  exportIntervalMs: number;
}

export const OtlpSettings: React.FC = () => {
  const [config, setConfig] = useState<OtlpConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    ApiClient.get<OtlpConfig>('/v1/observability/otlp/config')
      .then(data => setConfig(data))
      .catch(() => setError('Failed to load OTLP configuration'))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div className="p-6 flex items-center gap-2 text-sm text-(--theme-muted)">
        <span className="loading loading-spinner loading-sm"></span> Loading OTLP config...
      </div>
    );
  }

  if (error || !config) {
    return (
      <div className="p-6">
        <div className="text-sm text-error">{error || 'OTLP configuration unavailable'}</div>
      </div>
    );
  }

  return (
    <div className="p-6 space-y-4">
      <div className="flex items-center justify-between">
        <Typography.Heading level={4} className="tracking-tight">OpenTelemetry Export</Typography.Heading>
        <span className={`badge badge-sm ${config.enabled ? 'badge-success' : 'badge-ghost'}`}>
          {config.enabled ? 'Active' : 'Disabled'}
        </span>
      </div>
      <Typography.Text className="text-xs text-(--theme-muted)">
        OTLP span export configuration. Changes require server restart via application.properties.
      </Typography.Text>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mt-4">
        <div className="p-3 border border-(--theme-muted)/20 rounded-lg bg-(--theme-card)">
          <span className="font-bold tracking-wider uppercase text-[10px] text-(--theme-muted) block mb-1">Collector Endpoint</span>
          <span className="font-mono text-sm text-(--theme-foreground)">{config.endpoint}</span>
        </div>
        <div className="p-3 border border-(--theme-muted)/20 rounded-lg bg-(--theme-card)">
          <span className="font-bold tracking-wider uppercase text-[10px] text-(--theme-muted) block mb-1">Batch Size</span>
          <span className="font-mono text-sm text-(--theme-foreground)">{config.batchSize} spans</span>
        </div>
        <div className="p-3 border border-(--theme-muted)/20 rounded-lg bg-(--theme-card)">
          <span className="font-bold tracking-wider uppercase text-[10px] text-(--theme-muted) block mb-1">Export Interval</span>
          <span className="font-mono text-sm text-(--theme-foreground)">{config.exportIntervalMs}ms</span>
        </div>
        <div className="p-3 border border-(--theme-muted)/20 rounded-lg bg-(--theme-card)">
          <span className="font-bold tracking-wider uppercase text-[10px] text-(--theme-muted) block mb-1">Include Prompts</span>
          <span className={`badge badge-sm ${config.includePrompts ? 'badge-warning' : 'badge-ghost'}`}>
            {config.includePrompts ? 'Yes (sensitive data exposed)' : 'No (metadata only)'}
          </span>
        </div>
      </div>

      {!config.enabled && (
        <div className="mt-4 p-3 border border-dashed border-(--theme-muted)/30 rounded-lg text-xs text-(--theme-muted)">
          To enable OTLP export, set <code className="font-mono text-(--theme-foreground)">agentmanager.otlp.enabled=true</code> in application.properties and restart the server.
        </div>
      )}
    </div>
  );
};
