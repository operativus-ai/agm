import React, { useEffect, useState } from 'react';
import { LuEye, LuEyeOff, LuCheck, LuX } from 'react-icons/lu';
import { Typography } from '../../../shared/components/ui/Typography';
import { Alert } from '../../../shared/components/ui/Alert';
import { Input } from '../../../shared/components/ui/Input';
import { useUnsavedChangesGuard } from '../../../shared/hooks/useUnsavedChangesGuard';
import { ModelsApi } from '../../models/api/models-api';
import type { ModelConfig } from '../../models/types/models.types';
import { ProviderCredentialsApi } from '../api/provider-credentials-api';
import { PROVIDERS, type ProviderCredentialRequest, type ProviderCredentialResponse, type ProviderCredentialTestResponse } from '../types/provider-credentials.types';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSave: (id: string | null, data: ProviderCredentialRequest) => Promise<void>;
  credential?: ProviderCredentialResponse | null;
}

export const ProviderCredentialFormModal: React.FC<Props> = ({ isOpen, onClose, onSave, credential }) => {
  const isEditing = !!credential;
  const [provider, setProvider] = useState<string>('OPENAI');
  const [apiKey, setApiKey] = useState('');
  const [label, setLabel] = useState('');
  const [showApiKey, setShowApiKey] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { markDirty, resetDirty, guardedClose, showConfirm, confirmLeave, cancelLeave } = useUnsavedChangesGuard(onClose, isOpen);

  // ── Test-connection state (not persisted; separate from the form fields) ──────────────
  const [testModel, setTestModel] = useState('');
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<ProviderCredentialTestResponse | null>(null);
  const [availableModels, setAvailableModels] = useState<ModelConfig[]>([]);

  // Chat models for the selected provider, from the SAME source as the Agent → Engine dropdown
  // (the configured `models` table via ModelsApi.getModels), so the two lists match. Provider
  // casing differs across tables ("OpenAI" vs "OPENAI"), so compare case-insensitively. The test
  // endpoint needs the real provider model id (`modelName`), not the friendly `name` or row id.
  const modelOptions = availableModels
    .filter(m => (m.provider ?? '').toUpperCase() === provider.toUpperCase())
    .map(m => ({ value: m.modelName || m.name, label: m.name, available: m.available !== false }));

  useEffect(() => {
    if (credential) {
      setProvider(credential.provider);
      setLabel(credential.label ?? '');
      setApiKey('');
    } else {
      setProvider('OPENAI');
      setLabel('');
      setApiKey('');
    }
    setError(null);
    setTestResult(null);
  }, [credential, isOpen]);

  // Load the configured models once per open (mirrors AgentFormModal's Engine tab).
  useEffect(() => {
    if (!isOpen) return;
    ModelsApi.getModels({ size: 100 })
      .then(data => {
        const content = Array.isArray(data) ? data : (data?.content ?? []);
        setAvailableModels(content.filter(m => m.modelType === 'CHAT' || !m.modelType));
      })
      .catch(() => setAvailableModels([]));
  }, [isOpen]);

  // Default the "test against" model to the provider's first configured model (re-default on
  // provider change or when the model list loads), keeping a still-valid explicit selection.
  useEffect(() => {
    if (!isOpen) return;
    const values = availableModels
      .filter(m => (m.provider ?? '').toUpperCase() === provider.toUpperCase())
      .map(m => m.modelName || m.name);
    setTestModel(prev => (prev && values.includes(prev) ? prev : (values[0] ?? '')));
  }, [provider, isOpen, availableModels]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    // Key required on CREATE only. On EDIT a blank key means "keep the stored key" — the BE
    // never returns the plaintext, so forcing a re-type just to change a label is hostile.
    if (!isEditing && !apiKey.trim()) {
      setError('API key is required.');
      return;
    }
    try {
      setLoading(true);
      setError(null);
      const req: ProviderCredentialRequest = {
        provider,
        apiKey: apiKey.trim(),
        label: label.trim() || null,
      };
      await onSave(credential?.id ?? null, req);
      resetDirty();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save credential');
    } finally {
      setLoading(false);
    }
  };

  const handleTest = async () => {
    if (!testModel.trim()) {
      setTestResult({ success: false, provider, model: '', latencyMs: 0, message: 'Choose a model to test against.' });
      return;
    }
    try {
      setTesting(true);
      setTestResult(null);
      const result = await ProviderCredentialsApi.test({
        provider,
        // Typed key wins; blank => BE tests the stored key for this (org, provider).
        apiKey: apiKey.trim() || undefined,
        model: testModel.trim(),
      });
      setTestResult(result);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Test failed';
      setTestResult({ success: false, provider, model: testModel.trim(), latencyMs: 0, message });
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="modal modal-open">
      <div className="modal-box w-11/12 max-w-lg bg-obsidian-raised border border-obsidian-stroke p-0 overflow-hidden flex flex-col">
        <div className="flex items-center justify-between px-6 pt-6 pb-4 border-b border-obsidian-stroke shrink-0">
          <Typography.Heading level={3}>{isEditing ? 'Edit Provider Credential' : 'Add Provider Credential'}</Typography.Heading>
          <button type="button" className="btn btn-sm btn-circle bg-obsidian-surface border-obsidian-stroke text-theme-foreground hover:border-agent-blue/50" onClick={guardedClose}>✕</button>
        </div>

        {error && <Alert severity="error" className="mx-6 mt-4">{error}</Alert>}

        <form onSubmit={handleSubmit} className="space-y-4 overflow-y-auto flex-1 px-6 py-4">
          <div>
            <label className="block text-xs font-bold uppercase tracking-wider text-theme-muted mb-2">Provider</label>
            <select
              className="select select-bordered w-full bg-obsidian-surface border-obsidian-stroke"
              value={provider}
              onChange={e => { setProvider(e.target.value); markDirty(); }}
              disabled={isEditing}
            >
              {PROVIDERS.map(p => <option key={p} value={p}>{p}</option>)}
            </select>
            {isEditing && (
              <p className="text-xs text-theme-muted mt-1">
                Provider cannot be changed. Delete this row and create a new one if you need to switch providers.
              </p>
            )}
          </div>

          <Input
            label="API Key"
            type={showApiKey ? 'text' : 'password'}
            name="apiKey"
            value={apiKey}
            onChange={e => { setApiKey(e.target.value); markDirty(); }}
            required={!isEditing}
            placeholder={isEditing ? 'Leave blank to keep the current key' : 'sk-... / sk-ant-... / AIza...'}
            // A password-type field named "apiKey" gets autofilled by browsers / password
            // managers (Chrome ignores autoComplete="off" here), which silently injects a saved
            // password as the key → the test / save sends garbage. "new-password" stops the
            // existing-credential autofill; the data-* opt-outs cover 1Password / LastPass.
            autoComplete="new-password"
            data-1p-ignore
            data-lpignore="true"
            data-form-type="other"
            endIcon={showApiKey ? LuEyeOff : LuEye}
            onEndIconClick={() => setShowApiKey(s => !s)}
            endIconAriaLabel={showApiKey ? 'Hide API key' : 'Show API key'}
          />
          {isEditing && (
            <p className="text-xs text-theme-muted -mt-2">
              Current key tail: <code className="font-mono">{credential!.apiKeyPreview}</code>. Leave blank to keep it; enter a new value to rotate.
            </p>
          )}

          <Input
            label="Label (optional)"
            name="label"
            value={label}
            onChange={e => { setLabel(e.target.value); markDirty(); }}
            placeholder="e.g. Production OpenAI"
            maxLength={255}
          />

          {/* ── Test connection ─────────────────────────────────────────────────────────── */}
          <div className="pt-2 border-t border-obsidian-stroke space-y-2">
            <label className="block text-xs font-bold uppercase tracking-wider text-theme-muted">Test connection</label>
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <label className="block text-xs font-bold uppercase tracking-wider text-theme-muted mb-2">Test against model</label>
                <select
                  className="select select-bordered w-full bg-obsidian-surface border-obsidian-stroke"
                  value={testModel}
                  onChange={e => { setTestModel(e.target.value); setTestResult(null); }}
                  disabled={modelOptions.length === 0}
                >
                  {modelOptions.length === 0 && (
                    <option value="">No configured {provider} models — add one on the Models page</option>
                  )}
                  {modelOptions.map(o => (
                    <option key={o.value} value={o.value}>
                      {o.label}{o.available ? '' : ' — unavailable'}
                    </option>
                  ))}
                </select>
              </div>
              <button
                type="button"
                className="btn btn-sm bg-obsidian-surface border-obsidian-stroke text-theme-foreground hover:border-agent-blue/50 mb-[2px]"
                onClick={handleTest}
                disabled={testing || !testModel.trim()}
              >
                {testing ? <span className="loading loading-spinner loading-xs"></span> : 'Test connection'}
              </button>
            </div>
            <p className="text-xs text-theme-muted">
              Fires one tiny live request against the selected model. Uses the key entered above, or the saved key if the field is blank. Nothing is persisted.
            </p>
            {testResult && (
              <div
                className={`flex items-start gap-2 text-xs rounded-md px-3 py-2 border ${
                  testResult.success
                    ? 'border-success/40 bg-success/10 text-success'
                    : 'border-error/40 bg-error/10 text-error'
                }`}
              >
                {testResult.success
                  ? <LuCheck className="w-4 h-4 shrink-0 mt-px" />
                  : <LuX className="w-4 h-4 shrink-0 mt-px" />}
                <span className="break-words">
                  {testResult.success
                    ? `Key valid — ${testResult.model} reachable (${testResult.latencyMs} ms).`
                    : `Test failed${testResult.latencyMs ? ` after ${testResult.latencyMs} ms` : ''}: ${testResult.message ?? 'unknown error'}`}
                </span>
              </div>
            )}
          </div>
        </form>

        <div className="px-6 py-4 bg-obsidian-elevated border-t border-obsidian-stroke flex justify-end gap-2 shrink-0">
          <button type="button" className="btn btn-ghost btn-sm" onClick={guardedClose} disabled={loading}>Cancel</button>
          <button type="button" className="btn btn-primary btn-sm" onClick={handleSubmit} disabled={loading}>
            {loading ? <span className="loading loading-spinner loading-sm"></span> : isEditing ? 'Save Changes' : 'Add Credential'}
          </button>
        </div>
      </div>

      {/* Unsaved-changes confirm. Driven by the guard's showConfirm; ESC (handled inside the
          guard hook) dismisses it as "Keep editing". Rendered inline rather than via <Dialog>
          so it doesn't register a second document-level ESC listener competing with the guard.
          Fixed + z-[1000] + pointer-events-auto so it sits above (and is clickable over) the
          daisyUI .modal, which is z-999 and pointer-events:none. */}
      {showConfirm && (
        <div className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/60 p-4 pointer-events-auto" role="dialog" aria-modal="true">
          <div className="bg-obsidian-raised border border-obsidian-stroke rounded-lg shadow-2xl w-full max-w-xs p-5 space-y-4">
            <div>
              <Typography.Heading level={4}>Discard changes?</Typography.Heading>
              <p className="text-sm text-theme-muted mt-1">You have unsaved changes. Close this form without saving?</p>
            </div>
            <div className="flex justify-end gap-2">
              <button type="button" className="btn btn-ghost btn-sm" onClick={cancelLeave}>Keep editing</button>
              <button type="button" className="btn btn-error btn-sm" onClick={confirmLeave}>Discard</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
