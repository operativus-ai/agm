import React, { useEffect, useState } from 'react';
import { LuShuffle } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { Alert } from '../../../shared/components/ui/Alert';
import { Checkbox } from '../../../shared/components/ui/Checkbox';
import { Input } from '../../../shared/components/ui/Input';
import { RoutingConfigApi } from '../api/routing-config-api';
import type { OrgRoutingConfigRequest, OrgRoutingConfigResponse } from '../types/routing-config.types';
import { LuDatabaseZap } from 'react-icons/lu';

export const RoutingConfigPage: React.FC = () => {
  const [config, setConfig] = useState<OrgRoutingConfigResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const [defaultRouterAgentId, setDefaultRouterAgentId] = useState('');
  const [fallbackAgentId, setFallbackAgentId] = useState('');
  const [classifierModelId, setClassifierModelId] = useState('');
  const [llmClassifierEnabled, setLlmClassifierEnabled] = useState(false);
  const [ruleClassifierEnabled, setRuleClassifierEnabled] = useState(false);
  const [semanticScoringEnabled, setSemanticScoringEnabled] = useState(false);

  const [backfilling, setBackfilling] = useState(false);
  const [backfillMsg, setBackfillMsg] = useState<string | null>(null);

  useEffect(() => {
    void load();
  }, []);

  const load = async () => {
    try {
      setLoading(true);
      setError(null);
      const cfg = await RoutingConfigApi.get();
      applyConfig(cfg);
    } catch (err: any) {
      // 404 is normal — no config row yet
      if (err?.status !== 404) {
        setError(err?.message || 'Failed to load routing config');
      }
    } finally {
      setLoading(false);
    }
  };

  const applyConfig = (cfg: OrgRoutingConfigResponse) => {
    setConfig(cfg);
    setDefaultRouterAgentId(cfg.defaultRouterAgentId ?? '');
    setFallbackAgentId(cfg.fallbackAgentId ?? '');
    setClassifierModelId(cfg.classifierModelId ?? '');
    setLlmClassifierEnabled(cfg.llmClassifierEnabled);
    setRuleClassifierEnabled(cfg.ruleClassifierEnabled);
    setSemanticScoringEnabled(cfg.semanticScoringEnabled);
  };

  const handleSave = async () => {
    try {
      setSaving(true);
      setError(null);
      setSuccess(null);
      const req: OrgRoutingConfigRequest = {
        defaultRouterAgentId: defaultRouterAgentId.trim() || null,
        fallbackAgentId: fallbackAgentId.trim() || null,
        classifierModelId: classifierModelId.trim() || null,
        llmClassifierEnabled,
        ruleClassifierEnabled,
        semanticScoringEnabled,
      };
      const updated = await RoutingConfigApi.upsert(req);
      applyConfig(updated);
      setSuccess('Routing config saved');
    } catch (err: any) {
      setError(err?.message || 'Failed to save routing config');
    } finally {
      setSaving(false);
    }
  };

  const handleBackfill = async () => {
    try {
      setBackfilling(true);
      setError(null);
      setBackfillMsg(null);
      const res = await RoutingConfigApi.backfillEmbeddings();
      setBackfillMsg(
        `Backfilled routing vectors: ${res.embedded} of ${res.totalAgents} agent${res.totalAgents === 1 ? '' : 's'} embedded ` +
          `(agents without a description are skipped).`,
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to backfill routing embeddings');
    } finally {
      setBackfilling(false);
    }
  };

  return (
    <PageContainer variant="form">
      <PageHeader
        icon={LuShuffle}
        title="Routing Config"
        subtitle="Per-org cascade for universal dispatch (POST /api/runs)"
      />

      {error && <Alert severity="error" className="mb-4">{error}</Alert>}
      {success && <Alert severity="success" className="mb-4">{success}</Alert>}

      {loading ? (
        <p className="text-theme-muted">Loading…</p>
      ) : (
        <div className="space-y-6">
          <section>
            <h3 className="text-lg font-semibold mb-2">Strategy 1 — Default Router</h3>
            <p className="text-sm text-theme-muted mb-2">
              Must reference a ROUTER-strategy team agent. Short-circuits the cascade when set.
            </p>
            <Input
              label="Default router agent ID"
              value={defaultRouterAgentId}
              onChange={e => setDefaultRouterAgentId(e.target.value)}
              placeholder="(none)"
            />
          </section>

          <section>
            <h3 className="text-lg font-semibold mb-2">Strategy 2 — LLM Classifier</h3>
            <Checkbox
              label="Enable LLM classifier"
              checked={llmClassifierEnabled}
              onChange={e => setLlmClassifierEnabled(e.target.checked)}
            />
            <div className="mt-2">
              <Input
                label="Classifier model ID (optional)"
                value={classifierModelId}
                onChange={e => setClassifierModelId(e.target.value)}
                placeholder="System default if blank"
              />
            </div>
          </section>

          <section>
            <h3 className="text-lg font-semibold mb-2">Strategy 3 — Rule Classifier</h3>
            <Checkbox
              label="Enable rule classifier"
              checked={ruleClassifierEnabled}
              onChange={e => setRuleClassifierEnabled(e.target.checked)}
            />
            <div className="mt-1 ml-6">
              <Checkbox
                label="Use semantic scoring (pgvector cosine)"
                checked={semanticScoringEnabled}
                onChange={e => setSemanticScoringEnabled(e.target.checked)}
                disabled={!ruleClassifierEnabled}
              />
              <p className="text-xs text-theme-muted ml-1">
                When off, falls back to case-insensitive substring matching on agent description.
              </p>
              <div className="mt-3 ml-1 flex flex-wrap items-center gap-3">
                <Button variant="secondary" onClick={handleBackfill} disabled={backfilling} className="gap-1.5">
                  <LuDatabaseZap className="w-4 h-4" />
                  {backfilling ? 'Backfilling…' : 'Backfill embeddings'}
                </Button>
                <p className="text-xs text-theme-muted max-w-md">
                  Pre-computes routing vectors for this org's agents so the first semantic dispatch
                  doesn't pay the embed cost. Run after enabling semantic scoring or editing many
                  agent descriptions.
                </p>
              </div>
              {backfillMsg && (
                <Alert severity="success" className="mt-2 ml-1">{backfillMsg}</Alert>
              )}
            </div>
          </section>

          <section>
            <h3 className="text-lg font-semibold mb-2">Fallback</h3>
            <Input
              label="Fallback agent ID"
              value={fallbackAgentId}
              onChange={e => setFallbackAgentId(e.target.value)}
              placeholder="(none — unresolved dispatches return 404)"
            />
          </section>

          <div className="pt-4 flex gap-2">
            <Button onClick={handleSave} disabled={saving}>
              {saving ? 'Saving…' : 'Save'}
            </Button>
            {config && (
              <span className="text-xs text-theme-muted self-center">
                Updated: {config.updatedAt ?? '—'}
              </span>
            )}
          </div>
        </div>
      )}
    </PageContainer>
  );
};
