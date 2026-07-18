import React, { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { ModelConfig, ModelRequest, DefaultModelSlot } from '../types/models.types';
import { Typography } from '../../../shared/components/ui/Typography';
import { NO_AUTOFILL } from '../../../shared/utils/noAutofill';
import { Alert } from '../../../shared/components/ui/Alert';
import { Input } from '../../../shared/components/ui/Input';
import { Select } from '../../../shared/components/ui/Select';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { Badge } from '../../../shared/components/ui/Badge';
import { ModelsApi } from '../api/models-api';
import { useUnsavedChangesGuard } from '../../../shared/hooks/useUnsavedChangesGuard';
import { LuCircleCheck, LuCircleX, LuLoader, LuEye, LuEyeOff, LuTriangleAlert } from 'react-icons/lu';

/**
 * Sanity-check a Model Name string against the selected provider's known ID patterns.
 * Returns a user-facing warning when the string looks suspicious, or null when it
 * matches a known pattern (or the provider has no enforceable shape, like Ollama or
 * a custom OpenAI-compatible endpoint).
 *
 * Soft validation only — every check is a hint, not a block. Users can still save
 * since custom endpoints (vLLM, LMStudio, Azure OpenAI deployments) legitimately
 * use names that don't match the canonical cloud-provider format.
 */
function detectSuspiciousModelName(provider: string | undefined, modelName: string | undefined): string | null {
  if (!provider || !modelName) return null;
  const name = modelName.trim();
  if (!name) return null;
  const lower = name.toLowerCase();

  switch (provider) {
    case 'ANTHROPIC': {
      // Real Anthropic API IDs follow claude-{family}-{version}[-{date}], e.g.:
      //   claude-haiku-4-5-20251001, claude-opus-4-7, claude-sonnet-4-6
      // The common typo flips family/version: claude-4-5-haiku, claude-4-7-opus
      if (!lower.startsWith('claude-')) {
        return 'Anthropic model IDs start with "claude-". Example: claude-haiku-4-5-20251001';
      }
      const flipped = lower.match(/^claude-(\d+)-(\d+)-(opus|sonnet|haiku)$/);
      if (flipped) {
        return `Anthropic uses claude-{family}-{version} order. Try "claude-${flipped[3]}-${flipped[1]}-${flipped[2]}-<date>" (look up the current dated snapshot).`;
      }
      return null;
    }
    case 'OPENAI': {
      // OpenAI IDs: gpt-*, o1*, o3*, o4* — never decimal subversions like gpt-5.4.
      if (!/^(gpt-|o\d)/.test(lower)) {
        return 'OpenAI model IDs typically start with "gpt-", "o1", "o3", or "o4". For custom endpoints (LMStudio/vLLM/Azure) any name is fine.';
      }
      if (/^gpt-\d+\.\d+/.test(lower)) {
        return 'OpenAI uses suffixes like -mini / -turbo / -nano, not decimal subversions. Example: "gpt-4o" or "gpt-5", not "gpt-5.4".';
      }
      return null;
    }
    case 'GOOGLE': {
      if (!lower.startsWith('gemini-') && !lower.startsWith('text-embedding')) {
        return 'Google model IDs typically start with "gemini-" (chat) or "text-embedding-" (embeddings).';
      }
      return null;
    }
    case 'OLLAMA':
      // Local model names are user-defined; nothing to validate.
      return null;
    default:
      return null;
  }
}

interface ModelFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (id: string | null, data: ModelRequest) => Promise<void>;
  model?: ModelConfig | null;
  currentSettings?: Record<string, string>;
}

const SLOT_SETTINGS_KEYS: Record<DefaultModelSlot, string> = {
  ROUTER: 'DEFAULT_MODEL_ROUTER',
  FAST: 'DEFAULT_MODEL_FAST',
  HEAVY: 'DEFAULT_MODEL_HEAVY',
  EMBEDDING: 'DEFAULT_MODEL_EMBEDDING',
};

const SLOT_LABELS: Record<DefaultModelSlot, { label: string; description: string }> = {
  ROUTER: { label: 'Router', description: 'General routing & orchestration' },
  FAST: { label: 'Fast', description: 'Low-latency tasks' },
  HEAVY: { label: 'Heavy', description: 'Complex reasoning' },
  EMBEDDING: { label: 'Embedding', description: 'RAG & semantic search' },
};

type ProviderPreset = {
  label: string;
  provider: string;
  modelName: string;
  baseUrl?: string;
  supportsTools: boolean;
  supportsVision: boolean;
  supportsSystemInstructions: boolean;
  maxContextTokens?: number;
  maxOutputTokens?: number;
};

const PROVIDER_PRESETS: ProviderPreset[] = [
  { label: 'GPT-4o', provider: 'OPENAI', modelName: 'gpt-4o', supportsTools: true, supportsVision: true, supportsSystemInstructions: true, maxContextTokens: 128000, maxOutputTokens: 16384 },
  { label: 'GPT-4o Mini', provider: 'OPENAI', modelName: 'gpt-4o-mini', supportsTools: true, supportsVision: true, supportsSystemInstructions: true, maxContextTokens: 128000, maxOutputTokens: 16384 },
  { label: 'Claude 3.5 Sonnet', provider: 'ANTHROPIC', modelName: 'claude-3-5-sonnet-20241022', supportsTools: true, supportsVision: true, supportsSystemInstructions: true, maxContextTokens: 200000, maxOutputTokens: 8192 },
  { label: 'Claude 3.5 Haiku', provider: 'ANTHROPIC', modelName: 'claude-haiku-4-5-20251001', supportsTools: true, supportsVision: true, supportsSystemInstructions: true, maxContextTokens: 200000, maxOutputTokens: 8192 },
  { label: 'Gemini 2.5 Flash', provider: 'GOOGLE', modelName: 'gemini-2.5-flash', supportsTools: true, supportsVision: true, supportsSystemInstructions: true, maxContextTokens: 1000000, maxOutputTokens: 8192 },
  { label: 'Ollama (Local)', provider: 'OLLAMA', modelName: '', baseUrl: 'http://localhost:11434', supportsTools: true, supportsVision: false, supportsSystemInstructions: true },
];

const EMPTY_FORM: Partial<ModelRequest> = {
  name: '',
  provider: 'OPENAI',
  baseUrl: '',
  apiKey: '',
  modelName: '',
  supportsTools: true,
  supportsVision: false,
  supportsSystemInstructions: true,
  maxContextTokens: undefined,
  maxOutputTokens: undefined,
  thinkingBudgetTokens: undefined,
  modelType: 'CHAT',
  defaultSlot: undefined,
  rateLimitRpm: undefined,
};

// ── Step 1: Provider Config ────────────────────────────────────
interface ProviderConfigStepProps {
  formData: Partial<ModelRequest>;
  onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => void;
  isEditing: boolean;
  /** True when the model row already carries an encrypted per-model api_key on the BE. */
  apiKeyConfigured: boolean;
  testStatus: 'idle' | 'loading' | 'success' | 'error';
  testMessage: string | null;
  onTest: () => void;
}

const ProviderConfigStep: React.FC<ProviderConfigStepProps> = ({
  formData, onChange, isEditing, apiKeyConfigured, testStatus, testMessage, onTest,
}) => {
  const [showApiKey, setShowApiKey] = useState(false);
  const provider = formData.provider || 'OPENAI';
  // Live model-id catalog for the selected provider — powers the Model Name autocomplete.
  // Empty when no ProviderCredential is configured (e.g. local Ollama); the field stays free-text.
  const { data: catalog } = useQuery({
    queryKey: ['models', 'catalog', provider],
    queryFn: () => ModelsApi.getModelCatalog(provider),
    enabled: Boolean(provider),
    staleTime: 5 * 60_000,
    retry: false,
  });
  const catalogIds = catalog?.modelIds ?? [];
  return (
  <div className="space-y-4">
    <Select
      label="Provider"
      name="provider"
      value={formData.provider || 'OPENAI'}
      onChange={onChange}
      required
      options={[
        { label: 'OpenAI / OpenAI-Compatible (LMStudio, vLLM)', value: 'OPENAI' },
        { label: 'Anthropic', value: 'ANTHROPIC' },
        { label: 'Google (Gemini)', value: 'GOOGLE' },
        { label: 'Ollama (local)', value: 'OLLAMA' },
      ]}
    />

    <div>
      <Input
        label="Model Name"
        name="modelName"
        value={formData.modelName || ''}
        onChange={onChange}
        required
        list="model-catalog-options"
        placeholder="e.g. gpt-4o, claude-haiku-4-5-20251001, gemini-2.5-pro, llama3.2"
        description="The exact model identifier string sent to the provider API."
      />
      <datalist id="model-catalog-options">
        {catalogIds.map((id) => <option key={id} value={id} />)}
      </datalist>
      {catalogIds.length > 0 && (
        <p className="mt-1.5 text-xs text-(--theme-muted)">
          {catalogIds.length} model{catalogIds.length === 1 ? '' : 's'} available from {provider}&apos;s live catalog — start typing to pick one.
        </p>
      )}
      {(() => {
        const warning = detectSuspiciousModelName(formData.provider, formData.modelName);
        return warning ? (
          <p className="mt-1.5 text-xs text-warning flex items-start gap-1.5">
            <LuTriangleAlert className="w-3.5 h-3.5 shrink-0 mt-0.5" />
            <span>{warning}</span>
          </p>
        ) : null;
      })()}
    </div>

    <Input
      label="Base URL"
      type="url"
      name="baseUrl"
      value={formData.baseUrl || ''}
      onChange={onChange}
      placeholder="e.g. http://localhost:11434/v1"
      description="Leave blank to use the default cloud provider endpoint."
    />

    <div>
      <Input
        label="API Key"
        type={showApiKey ? 'text' : 'password'}
        name="apiKey"
        {...NO_AUTOFILL}
        value={formData.apiKey || ''}
        onChange={onChange}
        placeholder={isEditing ? (apiKeyConfigured ? '•••••••• (key configured)' : 'No per-model key — falls back to provider credential') : 'Enter API Key (optional for local providers)'}
        description={isEditing ? 'Leave blank to keep existing key. Key is not retrievable.' : undefined}
        endIcon={showApiKey ? LuEyeOff : LuEye}
        onEndIconClick={() => setShowApiKey(s => !s)}
        endIconAriaLabel={showApiKey ? 'Hide API key' : 'Show API key'}
      />
      {isEditing && (
        <p className={`mt-1.5 text-xs flex items-center gap-1.5 ${apiKeyConfigured ? 'text-success' : 'text-(--theme-muted)'}`}>
          <span className={`inline-block w-2 h-2 rounded-full ${apiKeyConfigured ? 'bg-success' : 'bg-(--theme-muted)/40'}`} />
          {apiKeyConfigured
            ? 'Per-model API key is configured.'
            : 'No per-model override — resolves against the org\'s ProviderCredential at call time.'}
        </p>
      )}
    </div>

    <div className="flex items-center gap-3">
      <button
        type="button"
        className="btn btn-outline btn-secondary btn-sm"
        onClick={onTest}
        disabled={testStatus === 'loading' || !formData.provider || !formData.modelName}
      >
        {testStatus === 'loading'
          ? <><LuLoader className="w-3.5 h-3.5 animate-spin" /> Testing…</>
          : 'Test Connection'}
      </button>
      {testStatus === 'success' && (
        <span className="flex items-center gap-1.5 text-xs text-active-green">
          <LuCircleCheck className="w-3.5 h-3.5" /> {testMessage}
        </span>
      )}
      {testStatus === 'error' && (
        <span className="flex items-center gap-1.5 text-xs text-error">
          <LuCircleX className="w-3.5 h-3.5" /> {testMessage}
        </span>
      )}
    </div>
  </div>
  );
};

// ── Step 2: Model Metadata ────────────────────────────────────
interface ModelMetadataStepProps {
  formData: Partial<ModelRequest>;
  onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => void;
  onSlotToggle: (slot: DefaultModelSlot) => void;
  currentSettings: Record<string, string>;
  modelId?: string;
}

const ModelMetadataStep: React.FC<ModelMetadataStepProps> = ({
  formData, onChange, onSlotToggle, currentSettings, modelId,
}) => {
  const getSlotOwnerId = (slot: DefaultModelSlot) => currentSettings[SLOT_SETTINGS_KEYS[slot]];

  return (
    <div className="space-y-4">
      <Input
        label="Configuration Name"
        name="name"
        value={formData.name || ''}
        onChange={onChange}
        required
        placeholder="e.g. Local Ollama, Production OpenAI"
      />

      <Select
        label="Model Type"
        name="modelType"
        value={formData.modelType || 'CHAT'}
        onChange={onChange}
        required
        options={[
          { label: 'Chat & Reasoning (LLM)', value: 'CHAT' },
          { label: 'Text Embeddings (Vector Store)', value: 'EMBEDDING' },
        ]}
        helpText="Chat models power agent reasoning. Embedding models are used for RAG and semantic search."
      />

      {/* Default Slot Selector */}
      <div>
        <span className="block text-xs font-bold text-(--theme-muted) uppercase tracking-wider mb-2">Default Slot</span>
        <div className="grid grid-cols-2 gap-2">
          {(Object.keys(SLOT_LABELS) as DefaultModelSlot[]).map(slot => {
            const ownerId = getSlotOwnerId(slot);
            const isThisModel = ownerId === modelId;
            const active = formData.defaultSlot === slot;
            const takenByOther = ownerId && !isThisModel && !active;
            return (
              <button
                key={slot}
                type="button"
                onClick={() => onSlotToggle(slot)}
                className={[
                  'flex flex-col items-start p-3 rounded-lg border text-left transition-colors',
                  active
                    ? 'border-agent-blue/60 bg-agent-blue/10 text-(--theme-foreground)'
                    : 'border-obsidian-stroke bg-obsidian-surface text-(--theme-muted) hover:border-agent-blue/30',
                ].join(' ')}
              >
                <div className="flex items-center justify-between w-full">
                  <span className="text-xs font-semibold">{SLOT_LABELS[slot].label}</span>
                  {active && <Badge variant="info" className="text-[9px]">Selected</Badge>}
                  {takenByOther && <Badge variant="neutral" outline className="text-[9px]">In use</Badge>}
                </div>
                <span className="text-[10px] mt-0.5 opacity-70">{SLOT_LABELS[slot].description}</span>
              </button>
            );
          })}
        </div>
        <p className="text-[10px] text-(--theme-muted) mt-1.5">Select a slot to make this the global default for that role. Selecting a slot already in use will reassign it.</p>
      </div>

      {/* Token Limits */}
      <div className="divider text-xs opacity-50 uppercase text-(--theme-muted)">Token Limits</div>
      <div className="grid grid-cols-3 gap-4 bg-obsidian-surface/50 p-4 rounded-xl border border-obsidian-stroke">
        <Input label="Max Context Tokens" type="number" name="maxContextTokens" size="sm"
          value={formData.maxContextTokens || ''} onChange={onChange} placeholder="e.g. 128000" />
        <Input label="Max Output Tokens" type="number" name="maxOutputTokens" size="sm"
          value={formData.maxOutputTokens || ''} onChange={onChange} placeholder="e.g. 4096" />
        <Input label="Thinking Budget" type="number" name="thinkingBudgetTokens" size="sm"
          value={formData.thinkingBudgetTokens || ''} onChange={onChange} placeholder="e.g. 2000" />
      </div>

      {/* Rate Limit (§6 M-12) */}
      <div className="divider text-xs opacity-50 uppercase text-(--theme-muted)">Rate Limit</div>
      <div className="bg-obsidian-surface/50 p-4 rounded-xl border border-obsidian-stroke space-y-2">
        <Input
          label="Requests per minute (optional)"
          type="number"
          name="rateLimitRpm"
          size="sm"
          min={1}
          max={60000}
          value={formData.rateLimitRpm ?? ''}
          onChange={onChange}
          placeholder="leave blank for no per-model cap"
        />
        <p className="text-xs text-(--theme-muted)">
          AGM rejects with HTTP 429 once this model exceeds the configured RPM. Sits in addition to
          the global per-user limit. Leave blank to disable the per-model gate.
        </p>
      </div>

      {/* Capabilities */}
      <div className="divider text-xs opacity-50 uppercase text-(--theme-muted)">Capabilities</div>
      <div className="grid grid-cols-1 gap-3 bg-obsidian-surface/50 p-4 rounded-xl border border-obsidian-stroke">
        {([
          { name: 'supportsTools', label: 'Supports Tools (Function Calling)', checked: formData.supportsTools !== false },
          { name: 'supportsSystemInstructions', label: 'Supports System Prompts', checked: formData.supportsSystemInstructions !== false },
          { name: 'supportsVision', label: 'Supports Vision (Multimodal)', checked: formData.supportsVision === true },
        ] as const).map(cap => (
          <label key={cap.name} className="cursor-pointer flex items-center justify-between p-3 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors">
            <span className="font-bold text-(--theme-foreground) text-sm">{cap.label}</span>
            <input type="checkbox" name={cap.name} checked={cap.checked} onChange={onChange}
              className="checkbox checkbox-sm checkbox-primary" />
          </label>
        ))}
      </div>
    </div>
  );
};

// ── Main Modal ────────────────────────────────────────────────
export const ModelFormModal: React.FC<ModelFormModalProps> = ({
  isOpen, onClose, onSave, model, currentSettings = {},
}) => {
  const [step, setStep] = useState<1 | 2>(1);
  const [formData, setFormData] = useState<Partial<ModelRequest>>(EMPTY_FORM);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [testStatus, setTestStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle');
  const [testMessage, setTestMessage] = useState<string | null>(null);
  const { markDirty, resetDirty, guardedClose, showConfirm, confirmLeave, cancelLeave } = useUnsavedChangesGuard(onClose, isOpen);

  useEffect(() => {
    setStep(1);
    setTestStatus('idle');
    setTestMessage(null);
    setError(null);
    if (model) {
      setFormData({
        name: model.name,
        provider: model.provider,
        baseUrl: model.baseUrl || '',
        apiKey: '',
        modelName: model.modelName || '',
        supportsTools: model.supportsTools ?? true,
        supportsVision: model.supportsVision ?? false,
        supportsSystemInstructions: model.supportsSystemInstructions ?? true,
        maxContextTokens: model.maxContextTokens,
        maxOutputTokens: model.maxOutputTokens,
        thinkingBudgetTokens: model.thinkingBudgetTokens,
        modelType: model.modelType || 'CHAT',
        defaultSlot: undefined,
        rateLimitRpm: model.rateLimitRpm ?? undefined,
      });
    } else {
      setFormData(EMPTY_FORM);
    }
  }, [model, isOpen]);

  if (!isOpen) return null;

  const isEditing = !!model;
  const step2Unlocked = !!(formData.provider && formData.modelName);
  const isStep1Valid = !!(formData.provider && (formData.apiKey || formData.baseUrl));

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const { name, value, type } = e.target;
    let val: unknown = type === 'checkbox' ? (e.target as HTMLInputElement).checked : value;
    if (type === 'number') val = value === '' ? undefined : Number(value);
    setFormData(prev => ({ ...prev, [name]: val }));
    markDirty();
  };

  const handleSlotToggle = (slot: DefaultModelSlot) => {
    setFormData(prev => ({ ...prev, defaultSlot: prev.defaultSlot === slot ? undefined : slot }));
    markDirty();
  };

  const applyPreset = (preset: ProviderPreset) => {
    setFormData(prev => ({
      ...prev,
      provider: preset.provider,
      modelName: preset.modelName,
      baseUrl: preset.baseUrl || '',
      supportsTools: preset.supportsTools,
      supportsVision: preset.supportsVision,
      supportsSystemInstructions: preset.supportsSystemInstructions,
      maxContextTokens: preset.maxContextTokens,
      maxOutputTokens: preset.maxOutputTokens,
    }));
    markDirty();
  };

  const handleTestConnection = async () => {
    try {
      setTestStatus('loading');
      setTestMessage(null);

      // Edit-mode with no re-typed key → ping the saved row so the BE uses its stored
      // (encrypted) apiKey instead of the empty form value. Skipping this branch would
      // force the BE to fall through to the per-(org, provider) ProviderCredential
      // lookup, which 400s when no default is configured.
      if (model?.id && !formData.apiKey?.trim()) {
        const result = await ModelsApi.pingModel(model.id);
        if (result.available) {
          setTestStatus('success');
          setTestMessage(`Connection successful (${result.latencyMs} ms)`);
        } else {
          setTestStatus('error');
          setTestMessage(result.errorMessage || 'Connection test failed');
        }
        return;
      }

      const payload: ModelRequest = {
        name: formData.name || 'test',
        provider: formData.provider!,
        baseUrl: formData.baseUrl,
        modelName: formData.modelName!,
        supportsTools: formData.supportsTools ?? true,
        supportsVision: formData.supportsVision ?? false,
        supportsSystemInstructions: formData.supportsSystemInstructions ?? true,
        modelType: formData.modelType || 'CHAT',
      };
      if (formData.apiKey) payload.apiKey = formData.apiKey;
      await ModelsApi.testConnection(payload);
      setTestStatus('success');
      setTestMessage('Connection successful');
    } catch (err: any) {
      setTestStatus('error');
      setTestMessage(err.message || 'Connection test failed');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      setLoading(true);
      setError(null);
      const payload: ModelRequest = {
        name: formData.name!,
        provider: formData.provider!,
        baseUrl: formData.baseUrl,
        modelName: formData.modelName,
        supportsTools: formData.supportsTools ?? true,
        supportsVision: formData.supportsVision ?? false,
        supportsSystemInstructions: formData.supportsSystemInstructions ?? true,
        maxContextTokens: formData.maxContextTokens,
        maxOutputTokens: formData.maxOutputTokens,
        thinkingBudgetTokens: formData.thinkingBudgetTokens,
        modelType: formData.modelType || 'CHAT',
        defaultSlot: formData.defaultSlot,
      };
      if (formData.apiKey?.trim()) payload.apiKey = formData.apiKey;
      // §6 M-12: only send rateLimitRpm when set; backend treats absent as "no override".
      // Sending `null` would currently be ignored by the service-layer null-keep-existing rule;
      // an explicit clear affordance is a follow-up.
      if (formData.rateLimitRpm) payload.rateLimitRpm = formData.rateLimitRpm;
      await onSave(model ? model.id : null, payload);
      resetDirty();
      onClose();
    } catch (err: any) {
      setError(err.message || 'Failed to save model configuration');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal modal-open">
      <div className="modal-box w-11/12 max-w-2xl max-h-[90vh] bg-obsidian-raised border border-obsidian-stroke p-0 overflow-hidden flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-6 pt-6 pb-4 border-b border-obsidian-stroke shrink-0">
          <div>
            <Typography.Heading level={3}>
              {isEditing ? 'Edit Custom Model' : 'Create Custom Model'}
            </Typography.Heading>
            <p className="text-xs text-(--theme-muted) mt-0.5">
              {step === 1 ? 'Step 1 of 2 — Provider Configuration' : 'Step 2 of 2 — Model Metadata'}
            </p>
            <div className="flex items-center gap-2 mt-1.5">
              {([1, 2] as const).map(s => (
                <button
                  key={s}
                  type="button"
                  disabled={s === 2 && !step2Unlocked}
                  onClick={() => (s === 1 || step2Unlocked) && setStep(s)}
                  className={[
                    'flex items-center gap-1.5 text-xs px-2 py-0.5 rounded transition-colors',
                    step === s
                      ? 'bg-agent-blue/20 text-agent-blue font-semibold'
                      : step2Unlocked || s === 1
                        ? 'text-(--theme-muted) hover:text-(--theme-foreground)'
                        : 'text-(--theme-muted) opacity-40 cursor-not-allowed',
                  ].join(' ')}
                >
                  <span className={[
                    'w-4 h-4 rounded-full flex items-center justify-center text-[10px] font-bold',
                    step === s ? 'bg-agent-blue text-white' : 'bg-obsidian-stroke',
                  ].join(' ')}>{s}</span>
                  {s === 1 ? 'Provider' : 'Details'}
                </button>
              ))}
            </div>
          </div>
          <button type="button"
            className="btn btn-sm btn-circle bg-obsidian-surface border-obsidian-stroke text-theme-foreground hover:border-agent-blue/50"
            onClick={guardedClose}>✕</button>
        </div>

        {error && <Alert severity="error" className="mx-6 mt-4">{error}</Alert>}

        <form onSubmit={handleSubmit} className="space-y-4 overflow-y-auto flex-1 px-6 py-4">
          {step === 1 && (
            <>
              {/* Quick Setup Presets */}
              {!isEditing && (
                <div>
                  <span className="block text-xs font-bold text-(--theme-muted) uppercase tracking-wider mb-2">Quick Setup</span>
                  <div className="flex flex-wrap gap-1.5">
                    {PROVIDER_PRESETS.map(preset => (
                      <button
                        key={preset.label}
                        type="button"
                        onClick={() => applyPreset(preset)}
                        className="btn btn-xs btn-outline border-obsidian-stroke text-(--theme-muted) hover:border-agent-blue/50 hover:text-(--theme-foreground)"
                      >
                        {preset.label}
                      </button>
                    ))}
                  </div>
                </div>
              )}
              <ProviderConfigStep
                formData={formData}
                onChange={handleChange}
                isEditing={isEditing}
                apiKeyConfigured={!!model?.apiKeyConfigured}
                testStatus={testStatus}
                testMessage={testMessage}
                onTest={handleTestConnection}
              />
            </>
          )}
          {step === 2 && (
            <ModelMetadataStep
              formData={formData}
              onChange={handleChange}
              onSlotToggle={handleSlotToggle}
              currentSettings={currentSettings}
              modelId={model?.id}
            />
          )}
        </form>

        {/* Footer */}
        <div className="px-6 py-4 bg-obsidian-elevated border-t border-obsidian-stroke flex justify-between shrink-0">
          <div>
            {step === 1 && (
              <button type="button" className="btn btn-outline btn-secondary btn-sm"
                onClick={() => isStep1Valid && setStep(2)}
                disabled={!isStep1Valid}>
                Next: Details →
              </button>
            )}
            {step === 2 && (
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setStep(1)}>
                ← Back
              </button>
            )}
          </div>
          <div className="flex gap-2">
            <button type="button" className="btn btn-ghost btn-sm" onClick={guardedClose} disabled={loading}>Cancel</button>
            <button type="button" className="btn btn-primary btn-sm" onClick={handleSubmit}
              disabled={loading || !formData.name || !formData.provider || !formData.modelName}>
              {loading ? <span className="loading loading-spinner loading-sm" /> : 'Save Configuration'}
            </button>
          </div>
        </div>
      </div>
      <div className="modal-backdrop" onClick={guardedClose} />

      <Dialog
        isOpen={showConfirm}
        setIsOpen={(open) => { if (!open) cancelLeave(); }}
        title="Unsaved Changes"
        content="You have unsaved changes. Are you sure you want to leave? Changes will be lost."
        severity="warning"
        confirmLabel="Leave"
        cancelLabel="Stay"
        onConfirm={confirmLeave}
        onCancel={cancelLeave}
      />
    </div>
  );
};
