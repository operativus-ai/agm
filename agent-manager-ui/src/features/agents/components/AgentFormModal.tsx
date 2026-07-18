import React, { useState, useEffect, useCallback } from 'react';
import type { AgentConfig, KnowledgeBase } from '../../../shared/types/api';
import { NO_AUTOFILL } from '../../../shared/utils/noAutofill';
import { Typography } from '../../../shared/components/ui/Typography';
import { Alert } from '../../../shared/components/ui/Alert';
import { MarkdownEditor } from '../../../shared/components/ui/MarkdownEditor';
import { ModelsApi } from '../../models/api/models-api';
import type { ModelConfig } from '../../models/types/models.types';
import { ToolsApi } from '../../../shared/api/toolsApi';
import type { ToolItem } from '../../../shared/api/toolsApi';
import { MultiSelectDropdown } from '../../../shared/components/ui/MultiSelectDropdown';
import type { MultiSelectOption } from '../../../shared/components/ui/MultiSelectDropdown';
import { AgentsApi } from '../api/agents-api';
import { KnowledgeBasesApi } from '../../knowledge/api/knowledge-bases-api';
import { AgentHookSelector } from './AgentHookSelector';
import { AgentPiiPolicySelector } from './AgentPiiPolicySelector';
import { configApi } from '../../../shared/api/configApi';
import type { AgentTemplateDTO } from '../../../shared/api/configApi';
import { credentialsApi } from '../api/credentialsApi';
import type { AgentCredentialDTO } from '../api/credentialsApi';
import { useAppDefaults } from '../../../shared/hooks/useAppDefaults';
import { useUnsavedChangesGuard } from '../../../shared/hooks/useUnsavedChangesGuard';
import { Dialog } from '../../../shared/components/ui/Dialog';

interface AgentFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (agent: Partial<AgentConfig>) => Promise<void>;
  agent?: AgentConfig | null; // Null/undefined means creating new
  initialTemplateId?: string | null; // Pre-selected template from full-page picker
}

type TabKey = 'general' | 'engine' | 'instructions' | 'capabilities' | 'team' | 'governance' | 'security' | 'docs';

/** Maps each editable field name to its owning tab so save-error focus can jump there. */
const FIELD_TO_TAB: Record<string, TabKey> = {
  // general
  agentId: 'general', name: 'general', description: 'general',
  primaryOwner: 'general', supportChannel: 'general',
  // engine
  model: 'engine', temperature: 'engine', topP: 'engine', maxTokens: 'engine',
  fallbackModelIds: 'engine', isReasoningEnabled: 'engine',
  // instructions
  instructions: 'instructions', systemPromptMode: 'instructions',
  // capabilities
  tools: 'capabilities', knowledgeBaseIds: 'capabilities', memoryEnabled: 'capabilities',
  addHistoryToMessages: 'capabilities', contextWindowSize: 'capabilities',
  // team
  isTeam: 'team', teamMembers: 'team',
  // governance
  requiresPiiRedaction: 'governance', approvedForProduction: 'governance',
  finOpsRiskTier: 'governance', securityTier: 'governance', enforceJsonOutput: 'governance',
  // security
  allowedRoles: 'security', credentials: 'security',
  // docs
  markdownDocs: 'docs', supportedLocales: 'docs',
  accessibilityCompatibility: 'docs', trainingDatasets: 'docs',
};

/** Iteration order for "first tab with an error". Matches visible tab order. */
const TAB_ORDER: TabKey[] = ['general', 'instructions', 'engine', 'capabilities', 'team', 'governance', 'security', 'docs'];

const ADVANCED_TABS: Set<TabKey> = new Set(['engine', 'capabilities', 'team', 'governance', 'security', 'docs']);

const TEMPLATE_ICONS: Record<string, string> = {
  chat: '\u{1F4AC}',
  search: '\u{1F50D}',
  'dollar-sign': '\u{1F4B0}',
  code: '\u{1F4BB}',
  globe: '\u{1F310}',
  braces: '\u{1F4CB}',
  headphones: '\u{1F3A7}',
  'pen-tool': '\u{270F}\u{FE0F}',
  shield: '\u{1F6E1}\u{FE0F}',
  database: '\u{1F5C4}\u{FE0F}',
  settings: '\u{2699}\u{FE0F}',
};

export const AgentFormModal: React.FC<AgentFormModalProps> = ({
  isOpen,
  onClose,
  onSave,
  agent,
  initialTemplateId
}) => {
  const { defaults: appDefaults } = useAppDefaults();
  const { markDirty, resetDirty, guardedClose, showConfirm, confirmLeave, cancelLeave } = useUnsavedChangesGuard(onClose, isOpen);

  const [formData, setFormData] = useState<Partial<AgentConfig>>({
    agentId: '',
    name: '',
    description: '',
    instructions: '',
    model: '',
    isReasoningEnabled: false,
    active: true,
  });

  // Progressive Disclosure: template picker + advanced toggle
  const [templates, setTemplates] = useState<AgentTemplateDTO[]>([]);
  const [templatesLoading, setTemplatesLoading] = useState(false);
  const [templatesError, setTemplatesError] = useState<string | null>(null);
  const [, setSelectedTemplate] = useState<string | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [wizardStep, setWizardStep] = useState<'template' | 'form'>('template');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [availableModels, setAvailableModels] = useState<ModelConfig[]>([]);
  const [availableSystemTools, setAvailableSystemTools] = useState<ToolItem[]>([]);
  const [activeTab, setActiveTab] = useState<TabKey>('general');
  const [knowledgeBaseOptions, setKnowledgeBaseOptions] = useState<MultiSelectOption[]>([]);
  const [agentOptions, setAgentOptions] = useState<MultiSelectOption[]>([]);
  const [kbLoading, setKbLoading] = useState(false);
  const [agentsLoading, setAgentsLoading] = useState(false);
  const [kbFetchError, setKbFetchError] = useState<string | null>(null);
  const [agentsFetchError, setAgentsFetchError] = useState<string | null>(null);
  const [pendingFallbackModel, setPendingFallbackModel] = useState<string>('');

  // Agent Identity / Credentials state
  const [credentials, setCredentials] = useState<AgentCredentialDTO[]>([]);
  const [credLoading, setCredLoading] = useState(false);
  const [newCred, setNewCred] = useState<Partial<AgentCredentialDTO>>({ credentialType: 'API_KEY', providerName: '', encryptedSecret: '', enabled: true });

  const fetchCredentials = useCallback(async (agentId: string) => {
    setCredLoading(true);
    try {
      const data = await credentialsApi.list(agentId);
      setCredentials(Array.isArray(data) ? data : []);
    } catch { setCredentials([]); }
    finally { setCredLoading(false); }
  }, []);

  const handleAddCredential = async () => {
    if (!formData.agentId || !newCred.providerName || !newCred.encryptedSecret) return;
    try {
      await credentialsApi.create(formData.agentId, newCred);
      setNewCred({ credentialType: 'API_KEY', providerName: '', encryptedSecret: '', enabled: true });
      fetchCredentials(formData.agentId);
    } catch (err: unknown) { setError(err instanceof Error ? err.message : 'Failed to add credential'); }
  };

  const handleDeleteCredential = async (credId: string) => {
    if (!formData.agentId) return;
    try {
      await credentialsApi.delete(formData.agentId, credId);
      fetchCredentials(formData.agentId);
    } catch (err: unknown) { setError(err instanceof Error ? err.message : 'Failed to delete credential'); }
  };

  useEffect(() => {
    if (isOpen) {
      // ModelsApi.getModels returns a PaginatedResponse<ModelConfig> per the BE
      // pagination contract ({content, totalElements, ...}). The old `Array.isArray(data)`
      // check silently failed because the response is a wrapper object, leaving the
      // model dropdown empty in the Create-Agent → Engine tab. Default page size is
      // 20 which is enough for the current seeded model set; bump to 100 for headroom.
      ModelsApi.getModels({ size: 100 })
        .then(data => {
            const content = Array.isArray(data) ? data : (data?.content ?? []);
            setAvailableModels(content.filter(m => m.modelType === 'CHAT' || !m.modelType));
        })
        .catch(err => console.error("Failed to load models for dropdown", err));

      ToolsApi.getTools()
        .then(data => {
            if (Array.isArray(data)) {
                setAvailableSystemTools(data);
            }
        })
        .catch(err => console.error("Failed to load tools", err));

      // Fetch knowledge bases for dropdown
      setKbLoading(true);
      setKbFetchError(null);
      KnowledgeBasesApi.getAll()
        .then(data => {
          if (Array.isArray(data)) {
            setKnowledgeBaseOptions(data.map((kb: KnowledgeBase) => ({
              value: kb.id,
              label: `${kb.name} (${kb.id.substring(0, 8)}...)`,
            })));
          }
        })
        .catch(() => setKbFetchError('Failed to load knowledge bases'))
        .finally(() => setKbLoading(false));

      // Fetch agent templates for wizard
      setTemplatesLoading(true);
      setTemplatesError(null);
      configApi.getAgentTemplates()
        .then(data => { if (Array.isArray(data)) setTemplates(data); })
        .catch(() => setTemplatesError('Failed to load templates.'))
        .finally(() => setTemplatesLoading(false));

      // Fetch credentials for existing agents
      if (agent?.agentId) {
        fetchCredentials(agent.agentId);
      }

      // Fetch agents for members dropdown
      setAgentsLoading(true);
      setAgentsFetchError(null);
      AgentsApi.getAgents()
        .then(data => {
          if (Array.isArray(data)) {
            setAgentOptions(data
              .filter((a: AgentConfig) => !a.isTeam)
              .map((a: AgentConfig) => ({
                value: a.agentId,
                label: `${a.name} (${a.agentId})`,
              }))
            );
          }
        })
        .catch(() => setAgentsFetchError('Failed to load agents'))
        .finally(() => setAgentsLoading(false));
    }
  }, [isOpen, fetchCredentials, agent?.agentId]);

  useEffect(() => {
    if (agent) {
      setFormData(agent);
    } else {
      setFormData({
        agentId: '',
        name: '',
        description: '',
        instructions: '',
        model: appDefaults?.defaultModelHeavy || '',
        isReasoningEnabled: false,
        active: true,
        markdownDocs: '',
        accessibilityCompatibility: '',
        supportedLocales: [],
        trainingDatasets: [],
        systemPromptMode: 'APPEND',
        temperature: appDefaults?.defaultTemperature ?? 0.7,
        topP: appDefaults?.defaultTopP ?? 0.9,
        frequencyPenalty: 0.0,
      });
    }
    setError(null);
    setFieldErrors({});
    setActiveTab('general');
    // Reset wizard state: editing skips template picker, creating starts with it
    // If initialTemplateId is provided from the full-page picker, skip to form
    setWizardStep(agent || initialTemplateId ? 'form' : 'template');
    setSelectedTemplate(null);
    setShowAdvanced(!!agent); // Editing always shows advanced
  }, [agent, isOpen, appDefaults?.defaultModelHeavy, appDefaults?.defaultTemperature, appDefaults?.defaultTopP, initialTemplateId]);

  // Auto-apply template when opened with initialTemplateId from full-page picker
  useEffect(() => {
    if (isOpen && initialTemplateId && templates.length > 0 && !agent) {
      const tpl = templates.find(t => t.id === initialTemplateId);
      if (tpl) {
        handleTemplateSelect(tpl);
      }
    }
  }, [isOpen, initialTemplateId, templates, agent]);

  const handleTemplateSelect = (template: AgentTemplateDTO) => {
    setSelectedTemplate(template.id);

    // Determine if template carries advanced settings worth showing
    const hasAdvancedDefaults = !!(
      template.defaultTools?.length ||
      template.requiresPiiRedaction ||
      template.enforceJsonOutput ||
      (template.securityTier && template.securityTier > 1) ||
      template.finOpsRiskTier
    );

    setFormData(prev => ({
      ...prev,
      agentTemplate: template.id,
      description: template.description || prev.description || '',
      model: template.defaultModel || prev.model || '',
      temperature: template.defaultTemperature ?? prev.temperature,
      tools: template.defaultTools && template.defaultTools.length > 0 ? template.defaultTools : prev.tools,
      requiresPiiRedaction: template.requiresPiiRedaction ?? false,
      memoryEnabled: template.memoryEnabled ?? false,
      securityTier: template.securityTier ?? prev.securityTier,
      systemPromptMode: template.systemPromptMode || 'APPEND',
      enforceJsonOutput: template.enforceJsonOutput ?? false,
      finOpsRiskTier: (template.finOpsRiskTier as AgentConfig['finOpsRiskTier']) ?? undefined,
    }));

    // Auto-expand advanced tabs so the user can see what the template configured
    if (hasAdvancedDefaults) {
      setShowAdvanced(true);
    }

    setWizardStep('form');
  };

  if (!isOpen) return null;

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
    const { name, value, type } = e.target;
    const val = type === 'checkbox' ? (e.target as HTMLInputElement).checked : value;
    setFormData(prev => ({ ...prev, [name]: val }));
    markDirty();
  };

  const handleToolToggle = (toolId: string) => {
    setFormData(prev => {
        const currentTools = prev.tools || [];
        if (currentTools.includes(toolId)) {
            return { ...prev, tools: currentTools.filter(t => t !== toolId) };
        } else {
            return { ...prev, tools: [...currentTools, toolId] };
        }
    });
    markDirty();
  };

  /**
   * Switch the active tab to the first tab (in TAB_ORDER) that owns a field with an error.
   * If that tab is an advanced tab, also flip showAdvanced on so the tab is visible in the strip.
   */
  const focusFirstErrorTab = (errs: Record<string, string>) => {
    const firstTab = TAB_ORDER.find(tab =>
      Object.keys(errs).some(field => FIELD_TO_TAB[field] === tab));
    if (firstTab) {
      if (ADVANCED_TABS.has(firstTab)) setShowAdvanced(true);
      setActiveTab(firstTab);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      setLoading(true);
      setError(null);
      setFieldErrors({});

      const errors: Record<string, string> = {};

      // BE has @NotBlank on AgentDefinition.id / name / modelId. The inputs are
      // marked required so the browser usually blocks, but the template-then-form
      // wizard path + programmatic submit can bypass that, leaving a confusing
      // 400 instead of inline form errors. Validate explicitly.
      if (!formData.agentId?.trim()) {
        errors.agentId = "Agent ID is required";
      }
      if (!formData.name?.trim()) {
        errors.name = "Name is required";
      }
      if (!formData.model?.trim()) {
        errors.model = "Model is required";
      }

      if (formData.knowledgeBaseIds && formData.knowledgeBaseIds.length > 0) {
        const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
        const invalidUuids = formData.knowledgeBaseIds.filter(id => !uuidRegex.test(id));
        if (invalidUuids.length > 0) {
          errors.knowledgeBaseIds = `Invalid UUIDs: ${invalidUuids.join(', ')}`;
        }
      }

      if (formData.agentId && !/^[a-zA-Z0-9_]+$/.test(formData.agentId)) {
        errors.agentId = "Agent ID can only contain letters, numbers, and underscores";
      }

      if (Object.keys(errors).length > 0) {
        setFieldErrors(errors);
        setError("Please correct the highlighted fields below.");
        focusFirstErrorTab(errors);
        setLoading(false);
        return;
      }

      await onSave(formData);
      resetDirty();
      onClose();
    } catch (err: unknown) {
      const apiErr = err as any;
      if (apiErr?.name === 'ApiError' && apiErr?.fields) {
          setFieldErrors(apiErr.fields);
          setError("Please correct the highlighted fields below.");
          focusFirstErrorTab(apiErr.fields);
      } else {
          setError(apiErr?.message || 'Failed to save agent');
      }
    } finally {
      setLoading(false);
    }
  };

  const isEditing = !!agent;

  const allTabs: { key: TabKey; label: string; advanced?: boolean }[] = [
    { key: 'general', label: 'General' },
    { key: 'instructions', label: 'Instructions' },
    { key: 'engine', label: 'Engine', advanced: true },
    { key: 'capabilities', label: 'Capabilities', advanced: true },
    { key: 'team', label: 'Team', advanced: true },
    { key: 'governance', label: 'Governance', advanced: true },
    { key: 'security', label: 'Security', advanced: true },
    { key: 'docs', label: 'Docs & QA', advanced: true },
  ];
  const tabs = showAdvanced ? allTabs : allTabs.filter(t => !t.advanced);

  // --- Reusable Section Header ---
  const SectionHeader = ({ title }: { title: string }) => (
    <div className="font-bold text-theme-muted tracking-wider uppercase text-xs mb-3 flex items-center gap-2">
      <span className="shrink-0">{title}</span>
      <div className="h-px bg-obsidian-stroke flex-1"></div>
    </div>
  );

  // --- Reusable Range Slider with Value Display ---
  const RangeSlider = ({ label, name, value, min, max, step, onChange }: {
    label: string; name: string; value: number | undefined; min: number; max: number; step: number;
    onChange: (val: number | undefined) => void;
  }) => (
    <div className="form-control">
      <label className="label py-1 flex justify-between">
        <span className="label-text font-bold text-theme-muted tracking-wider uppercase text-[10px]">{label}</span>
        <span className="font-mono text-xs text-agent-blue tabular-nums">{value ?? '—'}</span>
      </label>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        name={name}
        value={value ?? (min + max) / 2}
        onChange={(e) => onChange(parseFloat(e.target.value))}
        className="range range-xs range-primary"
      />
      <div className="flex justify-between text-[9px] text-theme-muted opacity-60 mt-1 px-0.5">
        <span>{min}</span>
        <span>{max}</span>
      </div>
    </div>
  );

  return (
    <div className="modal modal-open">
      <div className="modal-box relative max-w-5xl w-11/12 h-[90vh] flex flex-col bg-theme-background border border-obsidian-stroke shadow-2xl overflow-hidden p-6">
        <button
            type="button"
            className="btn btn-sm btn-circle btn-ghost absolute right-4 top-4 text-theme-muted hover:text-white z-10"
            onClick={guardedClose}
        >
          ✕
        </button>

        <div className="flex items-center justify-between mb-4 shrink-0 pr-8">
          <Typography.Heading level={3} className="text-theme-foreground">
            {isEditing ? 'Edit Agent' : wizardStep === 'template' ? 'Choose Agent Profile' : 'Create Agent'}
          </Typography.Heading>
          {(wizardStep === 'form' || isEditing) && (
            <label className="flex items-center gap-2 cursor-pointer select-none">
              <span className="text-xs font-bold uppercase tracking-wider text-theme-muted">Active</span>
              <input
                type="checkbox"
                name="active"
                checked={formData.active !== false}
                onChange={handleChange}
                className="toggle toggle-success toggle-sm"
              />
            </label>
          )}
        </div>

        {error && <Alert severity="error" className="mb-4 shrink-0">{error}</Alert>}

        {/* ═══════════════════════════════════════
            WIZARD STEP 1: Template Picker (Create only)
        ═══════════════════════════════════════ */}
        {wizardStep === 'template' && !isEditing && (
          <div className="flex-1 overflow-y-auto pr-2 custom-scrollbar">
            <p className="text-sm text-theme-muted mb-4">Select a profile to pre-configure sensible defaults. You can customize everything after.</p>
            {templatesError && (
              <div className="mb-3 p-3 rounded-lg border border-error/30 bg-error/5 text-sm text-error">
                {templatesError}
              </div>
            )}
            {templatesLoading ? (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {[1, 2, 3, 4, 5, 6].map(i => (
                  <div key={i} className="h-32 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
                ))}
              </div>
            ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {templates.map(tpl => (
                <button
                  key={tpl.id}
                  type="button"
                  className="group text-left bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-6 shadow-sm hover:border-primary/40 hover:shadow-lg transition-all duration-200 cursor-pointer"
                  onClick={() => handleTemplateSelect(tpl)}
                >
                  <div className="flex items-start justify-between mb-3">
                    <span className="text-2xl">{TEMPLATE_ICONS[tpl.icon] || '\u{2699}\u{FE0F}'}</span>
                    {tpl.finOpsRiskTier && (
                      <span className="text-[10px] font-mono uppercase tracking-wider text-(--theme-muted) bg-(--theme-muted)/10 px-2 py-0.5 rounded-full">
                        {tpl.finOpsRiskTier}
                      </span>
                    )}
                  </div>
                  <div className="font-semibold text-(--theme-foreground) mb-1 group-hover:text-primary transition-colors">
                    {tpl.name}
                  </div>
                  <div className="text-xs text-(--theme-muted) leading-relaxed mb-3">
                    {tpl.description}
                  </div>
                  {tpl.defaultTools && tpl.defaultTools.length > 0 && (
                    <div className="text-[10px] text-(--theme-muted) uppercase tracking-wider font-bold">
                      {tpl.defaultTools.length} tool{tpl.defaultTools.length !== 1 ? 's' : ''} pre-configured
                    </div>
                  )}
                </button>
              ))}
            </div>
            )}
          </div>
        )}

        {/* ═══════════════════════════════════════
            WIZARD STEP 2: Full Form (or Edit mode)
        ═══════════════════════════════════════ */}
        {(wizardStep === 'form' || isEditing) && (
        <form onSubmit={handleSubmit} className="flex flex-col flex-1 overflow-hidden text-left min-h-0" noValidate>

          {/* --- Tab Navigation + Advanced Toggle --- */}
          <div className="flex items-center gap-2 shrink-0 border-b border-obsidian-stroke mb-0">
            <div className="tabs tabs-bordered flex-nowrap overflow-x-auto flex-1 shadow-sm custom-scrollbar w-full">
              {tabs.map(tab => {
                const hasError = Object.keys(fieldErrors).some(field => FIELD_TO_TAB[field] === tab.key);
                return (
                  <button
                    key={tab.key}
                    type="button"
                    className={`tab font-bold tracking-wider uppercase text-[11px] whitespace-nowrap min-w-max pb-3 ${activeTab === tab.key ? 'tab-active text-agent-blue border-agent-blue' : 'text-theme-muted hover:text-white'} ${hasError ? 'text-error' : ''}`}
                    onClick={() => setActiveTab(tab.key)}
                >
                  {tab.label}
                  {hasError && <span className="ml-1 inline-block w-2 h-2 rounded-full bg-error align-middle" title="Has validation errors" />}
                </button>
                );
              })}
            </div>
            <button
              type="button"
              className={`btn btn-xs btn-ghost whitespace-nowrap shrink-0 mr-1 mb-1 ${showAdvanced ? 'text-agent-blue' : 'text-theme-muted'}`}
              onClick={() => { setShowAdvanced(!showAdvanced); if (!showAdvanced && ['engine','capabilities','team','governance','security','docs'].includes(activeTab)) { /* keep tab */ } }}
            >
              {showAdvanced ? 'Hide Advanced' : 'Advanced'}
            </button>
          </div>

          {/* --- Scrollable Tab Content --- */}
          <div className={`flex-1 overflow-y-auto pr-2 custom-scrollbar ${activeTab === 'instructions' ? 'pt-0 flex flex-col min-h-0' : 'pt-5 pb-4 space-y-5'}`}>

            {/* ═══════════════════════════════════════
                TAB: GENERAL — Identity & Ownership
            ═══════════════════════════════════════ */}
            {activeTab === 'general' && (
              <>
                <SectionHeader title="Identity" />
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Agent ID</span></label>
                    <input
                      type="text"
                      name="agentId"
                      value={formData.agentId || ''}
                      onChange={handleChange}
                      disabled={isEditing}
                      required
                      className={`input input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue ${fieldErrors.agentId ? 'input-error' : ''}`}
                      placeholder="e.g. support_agent"
                    />
                    {fieldErrors.agentId && <span className="label-text-alt text-error mt-1">{fieldErrors.agentId}</span>}
                    {isEditing && !fieldErrors.agentId && <span className="label-text-alt mt-1 text-theme-muted opacity-70 text-[10px]">Immutable after creation</span>}
                  </div>

                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Display Name</span></label>
                    <input
                      type="text"
                      name="name"
                      value={formData.name || ''}
                      onChange={handleChange}
                      required
                      className={`input input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue ${fieldErrors.name ? 'input-error' : ''}`}
                      placeholder="e.g. Support Agent"
                    />
                    {fieldErrors.name && <span className="label-text-alt text-error mt-1">{fieldErrors.name}</span>}
                  </div>
                </div>

                <div className="form-control mt-4">
                  <label className="label py-1">
                      <span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Description</span>
                  </label>
                  <textarea
                    name="description"
                    value={formData.description || ''}
                    onChange={handleChange}
                    required
                    className={`textarea textarea-bordered w-full h-24 bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue resize-none ${fieldErrors.description ? 'textarea-error' : ''}`}
                    placeholder="Brief description of the agent's purpose (Used for LLM routing)"
                  />
                  {fieldErrors.description && <span className="label-text-alt text-error mt-1">{fieldErrors.description}</span>}
                </div>

                <SectionHeader title="Ownership" />
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Primary Owner</span></label>
                    <input type="text" name="primaryOwner" value={formData.primaryOwner || ''} onChange={handleChange} placeholder="e.g. data_science_team" className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue" />
                  </div>
                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Support Channel</span></label>
                    <input type="text" name="supportChannel" value={formData.supportChannel || ''} onChange={handleChange} placeholder="e.g. #agent-support" className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue" />
                  </div>
                </div>
              </>
            )}

            {/* ═══════════════════════════════════════
                TAB: ENGINE — AI Model & Behavior
            ═══════════════════════════════════════ */}
            {activeTab === 'engine' && (
              <>
                <SectionHeader title="Primary Model" />
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Engine (Model)</span></label>
                    <select
                      name="model"
                      value={formData.model || ''}
                      onChange={handleChange}
                      required
                      className="select select-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                    >
                      <option value="" disabled>Select a Model</option>
                      {availableModels.map(m => (
                          <option key={m.id} value={m.id}>
                              {m.name} ({m.provider}){m.available === false ? ' — unavailable' : ''}
                          </option>
                      ))}
                      {availableModels.length === 0 && appDefaults?.defaultModelHeavy && (
                          <option value={appDefaults.defaultModelHeavy}>{appDefaults.defaultModelHeavy} (System Default)</option>
                      )}
                      {isEditing && !availableModels.find(m => m.id === formData.model) && formData.model && (
                          <option value={formData.model}>{formData.model} (Unregistered)</option>
                      )}
                    </select>
                    {(() => {
                        // §7 Model Pinger: warn when the currently-selected model failed its last
                        // liveness probe. The agent-runs path does NOT gate on `available` — runs
                        // still go through — so this is a soft heads-up, not a block.
                        const selected = availableModels.find(m => m.id === formData.model);
                        if (selected?.available !== false) return null;
                        return (
                            <div className="mt-2 text-xs text-amber-400">
                                ⚠ Last liveness check failed for this model
                                {selected?.lastPingedAt ? ` (checked ${new Date(selected.lastPingedAt).toLocaleString()})` : ''}.
                                Runs may fail at inference time.
                            </div>
                        );
                    })()}
                  </div>

                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">System Prompt Mode</span></label>
                    <select
                      name="systemPromptMode"
                      value={formData.systemPromptMode || 'APPEND'}
                      onChange={(e) => setFormData(prev => ({ ...prev, systemPromptMode: e.target.value }))}
                      className="select select-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                    >
                      <option value="APPEND">APPEND — Inject instructions into standard chat history</option>
                      <option value="REPLACE">REPLACE — Overwrite native model behavior (Caution)</option>
                    </select>
                  </div>
                </div>

                <SectionHeader title="Fallback Model Chain" />
                <div className="flex flex-col gap-3">
                  <p className="text-[10px] text-theme-muted leading-relaxed">
                    Ordered list of models to try when the primary model is unavailable or rate-limited. Tried in sequence, top-first.
                  </p>
                  {/* Ordered fallback list */}
                  {(formData.fallbackModelIds || []).length > 0 && (
                    <div className="flex flex-col gap-1">
                      {(formData.fallbackModelIds || []).map((mid, idx) => {
                        const meta = availableModels.find(m => m.id === mid);
                        const label = meta ? `${meta.name} (${meta.provider})` : mid;
                        const unavailable = meta?.available === false;
                        return (
                          <div key={mid + idx} className="flex items-center gap-2 px-3 py-1.5 rounded bg-obsidian-surface border border-obsidian-stroke">
                            <span className="text-[10px] font-mono text-theme-muted w-5 shrink-0 text-right">{idx + 1}.</span>
                            <span className={`flex-1 text-xs font-mono ${unavailable ? 'text-amber-400' : 'text-theme-foreground'}`}>
                              {label}{unavailable ? ' — unavailable' : ''}
                            </span>
                            <button
                              type="button"
                              disabled={idx === 0}
                              onClick={() => setFormData(prev => {
                                const arr = [...(prev.fallbackModelIds || [])];
                                [arr[idx - 1], arr[idx]] = [arr[idx], arr[idx - 1]];
                                return { ...prev, fallbackModelIds: arr };
                              })}
                              className="btn btn-ghost btn-xs px-1 disabled:opacity-30"
                              title="Move up"
                            >↑</button>
                            <button
                              type="button"
                              disabled={idx === (formData.fallbackModelIds || []).length - 1}
                              onClick={() => setFormData(prev => {
                                const arr = [...(prev.fallbackModelIds || [])];
                                [arr[idx + 1], arr[idx]] = [arr[idx], arr[idx + 1]];
                                return { ...prev, fallbackModelIds: arr };
                              })}
                              className="btn btn-ghost btn-xs px-1 disabled:opacity-30"
                              title="Move down"
                            >↓</button>
                            <button
                              type="button"
                              onClick={() => setFormData(prev => ({
                                ...prev,
                                fallbackModelIds: (prev.fallbackModelIds || []).filter((_, i) => i !== idx),
                              }))}
                              className="btn btn-ghost btn-xs px-1 text-error hover:text-error"
                              title="Remove"
                            >×</button>
                          </div>
                        );
                      })}
                    </div>
                  )}
                  {/* Add fallback model */}
                  <div className="flex gap-2 items-center">
                    <select
                      value={pendingFallbackModel}
                      onChange={e => setPendingFallbackModel(e.target.value)}
                      className="select select-sm select-bordered flex-1 bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                    >
                      <option value="">Add a fallback model…</option>
                      {availableModels
                        .filter(m => m.id !== formData.model && !(formData.fallbackModelIds || []).includes(m.id))
                        .map(m => (
                          <option key={m.id} value={m.id}>
                            {m.name} ({m.provider}){m.available === false ? ' — unavailable' : ''}
                          </option>
                        ))}
                    </select>
                    <button
                      type="button"
                      disabled={!pendingFallbackModel}
                      onClick={() => {
                        if (!pendingFallbackModel) return;
                        setFormData(prev => ({
                          ...prev,
                          fallbackModelIds: [...(prev.fallbackModelIds || []), pendingFallbackModel],
                        }));
                        setPendingFallbackModel('');
                      }}
                      className="btn btn-sm btn-outline border-obsidian-stroke hover:border-agent-blue disabled:opacity-40"
                    >
                      + Add
                    </button>
                  </div>
                </div>

                <SectionHeader title="Behavioral Flags" />
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                  <div className="form-control">
                    <label className="cursor-pointer p-4 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors w-full min-w-0 h-full flex flex-col gap-3">
                      <div className="flex flex-col min-w-0">
                        <span className="font-bold tracking-wider uppercase text-[10px] text-theme-foreground block">High Reasoning</span>
                        <span className="text-[10px] text-theme-muted mt-0.5 block">Enable step-by-step cognitive traces</span>
                      </div>
                      <input type="checkbox" name="isReasoningEnabled" checked={formData.isReasoningEnabled || false} onChange={handleChange} className="checkbox checkbox-sm checkbox-primary" />
                    </label>
                  </div>
                  <div className="form-control">
                    <label className="cursor-pointer p-4 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors w-full min-w-0 h-full flex flex-col gap-3">
                      <div className="flex flex-col min-w-0">
                        <span className="font-bold tracking-wider uppercase text-[10px] text-theme-foreground block">Enforce Strict JSON</span>
                        <span className="text-[10px] text-theme-muted mt-0.5 block">Disable free-form text output</span>
                      </div>
                      <input type="checkbox" name="enforceJsonOutput" checked={formData.enforceJsonOutput || false} onChange={handleChange} className="checkbox checkbox-sm checkbox-primary" />
                    </label>
                  </div>
                  <div className="form-control">
                    <label className="cursor-pointer p-4 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors w-full min-w-0 h-full flex flex-col gap-3">
                      <div className="flex flex-col min-w-0">
                        <span className="font-bold tracking-wider uppercase text-[10px] text-theme-foreground block">Conversational History</span>
                        <span className="text-[10px] text-theme-muted mt-0.5 block">Include prior dialogue turns</span>
                      </div>
                      <input type="checkbox" name="addHistoryToMessages" checked={formData.addHistoryToMessages ?? true} onChange={handleChange} className="checkbox checkbox-sm checkbox-primary" />
                    </label>
                  </div>
                </div>
              </>
            )}

            {/* ═══════════════════════════════════════
                TAB: INSTRUCTIONS — Full-Width Markdown Editor
            ═══════════════════════════════════════ */}
            {activeTab === 'instructions' && (
              <div className="flex flex-col flex-1 min-h-0 pt-4">
                <div className="flex justify-between items-end mb-2 shrink-0 px-1">
                   <span className="font-bold text-theme-muted tracking-wider uppercase text-xs">System Instructions</span>
                   <span className="text-[10px] text-theme-muted opacity-70 border border-obsidian-stroke px-2 py-0.5 rounded bg-obsidian-surface">Markdown — Full Width Editor</span>
                </div>
                <div className="flex-1 border border-obsidian-stroke rounded-lg overflow-hidden flex flex-col focus-within:border-agent-blue transition-colors relative min-h-0">
                  <MarkdownEditor
                    value={formData.instructions || ''}
                    onValueChange={(val) => setFormData(prev => ({ ...prev, instructions: val || '' }))}
                    height="100%"
                    className="flex-1 w-full border-0!"
                  />
                </div>
                {fieldErrors.instructions && <span className="label-text-alt text-error mt-2">{fieldErrors.instructions}</span>}
              </div>
            )}

            {/* ═══════════════════════════════════════
                TAB: CAPABILITIES — Tools, Memory, RAG
            ═══════════════════════════════════════ */}
            {activeTab === 'capabilities' && (
              <>
                <SectionHeader title="Functions & Tools" />
                {(() => {
                    const selectedModelConfig = availableModels.find(m => m.id === formData.model);
                    const toolsSupported = selectedModelConfig ? selectedModelConfig.supportsTools !== false : true;
                    
                    return (
                        <>
                            {!toolsSupported && (
                                <Alert severity="warning" className="text-sm mb-4">
                                    The selected model does not support function calling.
                                </Alert>
                            )}
                            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                              {[
                                { id: 'search_knowledge_base', label: 'Knowledge Base Search (RAG)', desc: 'Index query access.' },
                                { id: 'web_crawl', label: 'Web Scraper', desc: 'URL fetching & scraping.' },
                                { id: 'delegate_to_agent', label: 'Team Delegation', desc: 'Supervisor mode dispatching.' }
                              ].concat(availableSystemTools.filter(t => !['search_knowledge_base', 'web_crawl', 'delegate_to_agent'].includes(t.id))).map(tool => (
                                <div key={tool.id} className={`form-control border border-obsidian-stroke rounded-lg bg-obsidian-surface transition-colors w-full overflow-hidden ${!toolsSupported ? 'opacity-40 cursor-not-allowed' : 'hover:border-agent-blue/50'}`}>
                                  <label className={`p-3 w-full min-w-0 flex flex-col gap-2 ${toolsSupported ? 'cursor-pointer' : ''}`}>
                                    <div className="flex flex-col flex-1 min-w-0 overflow-hidden">
                                      <span className="font-bold text-theme-foreground text-sm truncate">{tool.label}</span>
                                      <span className="text-xs text-theme-muted font-mono mt-1 whitespace-normal wrap-break-word leading-relaxed">{tool.id} — {tool.desc}</span>
                                    </div>
                                    <input
                                      type="checkbox"
                                      className="checkbox checkbox-sm checkbox-primary"
                                      checked={(formData.tools || []).includes(tool.id) && toolsSupported}
                                      onChange={() => handleToolToggle(tool.id)}
                                      disabled={!toolsSupported}
                                    />
                                  </label>
                                </div>
                              ))}
                            </div>
                        </>
                    );
                })()}

                <details className="group mt-2">
                  <summary className="flex items-center gap-2 cursor-pointer list-none select-none py-2 border-t border-obsidian-stroke text-theme-muted hover:text-theme-foreground transition-colors">
                    <svg className="w-3.5 h-3.5 transition-transform group-open:rotate-90 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}><path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" /></svg>
                    <span className="font-bold tracking-wider uppercase text-xs">Memory &amp; Context Recovery</span>
                  </summary>
                  <div className="pt-4 space-y-4">
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <div className="form-control">
                        <label className="cursor-pointer p-4 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors w-full min-w-0 h-full flex flex-col gap-3">
                          <div className="flex flex-col min-w-0">
                            <span className="font-bold tracking-wider uppercase text-[10px] text-theme-foreground block">Semantic Memory Graph</span>
                            <span className="text-[10px] text-theme-muted mt-0.5 block">Extract insights into long-term state</span>
                          </div>
                          <input type="checkbox" name="memoryEnabled" checked={formData.memoryEnabled || false} onChange={handleChange} className="checkbox checkbox-sm checkbox-primary" />
                        </label>
                      </div>
                      <div className="form-control">
                        <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Context Window Size</span></label>
                        <input
                          type="number"
                          name="contextWindowSize"
                          value={formData.contextWindowSize || ''}
                          onChange={(e) => setFormData(prev => ({ ...prev, contextWindowSize: parseInt(e.target.value) || 0 }))}
                          className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                          placeholder="4096"
                        />
                        <span className="label-text-alt mt-1 text-theme-muted opacity-70 text-[10px]">Token eviction threshold for the Agent's shared ephemeral memory.</span>
                      </div>
                    </div>
                    <MultiSelectDropdown
                      label="Knowledge Base Embeddings"
                      value={formData.knowledgeBaseIds || []}
                      onChange={(val) => setFormData(prev => ({ ...prev, knowledgeBaseIds: val }))}
                      options={knowledgeBaseOptions}
                      placeholder="Select knowledge bases..."
                      emptyMessage="No knowledge bases registered"
                      loading={kbLoading}
                      fetchError={kbFetchError}
                      error={fieldErrors.knowledgeBaseIds}
                    />
                  </div>
                </details>
              </>
            )}

            {/* ═══════════════════════════════════════
                TAB: TEAM — Multi-Agent Orchestration
            ═══════════════════════════════════════ */}
            {activeTab === 'team' && (
              <>
                <SectionHeader title="Multi-Agent Supervision" />
                <div className="form-control w-full">
                  <label className="cursor-pointer p-4 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors w-full min-w-0 flex flex-col gap-3">
                    <div className="flex flex-col flex-1 min-w-0">
                      <span className="font-bold tracking-wider uppercase text-xs text-theme-foreground block">Is Team Supervisor</span>
                      <span className="text-xs text-theme-muted mt-1 block whitespace-normal wrap-break-word leading-relaxed">Enables Routing/Coordinator execution patterns</span>
                    </div>
                    <input type="checkbox" name="isTeam" checked={formData.isTeam || false} onChange={handleChange} className="toggle toggle-info" />
                  </label>
                </div>

                {formData.isTeam && (
                  <>
                    <div className="form-control mt-4">
                      <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Orchestration Mode</span></label>
                      <select name="teamMode" value={formData.teamMode || 'COORDINATOR'} onChange={handleChange} className="select select-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue">
                        <option value="COORDINATOR">Coordinator (Hub & Spoke / Dispatcher)</option>
                        <option value="SWARM">Swarm (Autonomous Handoffs)</option>
                      </select>
                    </div>

                    <div className="form-control mt-4">
                      <MultiSelectDropdown
                        label="Sub-Agent Members"
                        value={formData.members || []}
                        onChange={(val) => setFormData(prev => ({ ...prev, members: val }))}
                        options={agentOptions}
                        placeholder="Select sub-agents..."
                        emptyMessage="No agents registered"
                        loading={agentsLoading}
                        fetchError={agentsFetchError}
                      />
                    </div>
                  </>
                )}

                <SectionHeader title="Access Control" />
                <div className="form-control">
                  <MultiSelectDropdown
                    label="Allowed IAM Roles"
                    value={formData.allowedRoles || []}
                    onChange={(val) => setFormData(prev => ({ ...prev, allowedRoles: val }))}
                    options={[
                      { value: 'ROLE_ADMIN', label: 'ROLE_ADMIN' },
                      { value: 'ROLE_AGENT_USER', label: 'ROLE_AGENT_USER' },
                      { value: 'ROLE_AGENT_MANAGER', label: 'ROLE_AGENT_MANAGER' },
                      { value: 'ROLE_VIEWER', label: 'ROLE_VIEWER' },
                      { value: 'ROLE_OPERATOR', label: 'ROLE_OPERATOR' },
                      { value: 'ROLE_AUDITOR', label: 'ROLE_AUDITOR' },
                    ]}
                    placeholder="Select allowed roles..."
                    emptyMessage="No roles available"
                  />
                </div>
              </>
            )}

            {/* ═══════════════════════════════════════
                TAB: GOVERNANCE — Tuning, FinOps, Optimization
            ═══════════════════════════════════════ */}
            {activeTab === 'governance' && (
              <>
                <SectionHeader title="Generative Tuning" />
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-6">
                  <RangeSlider
                    label="Temperature"
                    name="temperature"
                    value={formData.temperature}
                    min={0}
                    max={2}
                    step={0.1}
                    onChange={(val) => setFormData(prev => ({ ...prev, temperature: val }))}
                  />
                  <div>
                    <RangeSlider
                      label="Top P"
                      name="topP"
                      value={formData.topP}
                      min={0}
                      max={1}
                      step={0.05}
                      onChange={(val) => setFormData(prev => ({ ...prev, topP: val }))}
                    />
                    {availableModels.find(m => m.id === formData.model)?.provider === 'ANTHROPIC' && (
                      <p className="mt-1 text-xs text-(--theme-muted) italic">
                        Anthropic models reject {`temperature + top_p`} together — AGM suppresses top_p and sends temperature only. This value is stored but not transmitted.
                      </p>
                    )}
                  </div>
                  <RangeSlider
                    label="Frequency Penalty"
                    name="frequencyPenalty"
                    value={formData.frequencyPenalty}
                    min={-2}
                    max={2}
                    step={0.1}
                    onChange={(val) => setFormData(prev => ({ ...prev, frequencyPenalty: val }))}
                  />
                </div>

                <SectionHeader title="FinOps & Execution Limits" />
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Max Concurrent Executions</span></label>
                    <input
                      type="number"
                      name="maxConcurrentExecutions"
                      value={formData.maxConcurrentExecutions || ''}
                      onChange={(e) => setFormData(prev => ({ ...prev, maxConcurrentExecutions: e.target.value ? parseInt(e.target.value) : undefined }))}
                      className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                      placeholder="e.g. 5"
                    />
                    <span className="label-text-alt mt-1 text-theme-muted opacity-70 text-[10px]">Protects system from parallel DDOS abuse</span>
                  </div>
                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">FinOps Token Budget (Lifetime)</span></label>
                    <input
                      type="number"
                      name="finOpsTokenBudget"
                      value={formData.finOpsTokenBudget || ''}
                      onChange={(e) => setFormData(prev => ({ ...prev, finOpsTokenBudget: e.target.value ? parseInt(e.target.value) : undefined }))}
                      className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                      placeholder="e.g. 1000000"
                    />
                    <span className="label-text-alt mt-1 text-theme-muted opacity-70 text-[10px]">Max tokens before requiring refill</span>
                  </div>
                </div>

                <SectionHeader title="Optimization Sovereignty" />
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                  <div className="form-control">
                    <label className="label py-1 flex justify-between">
                      <span className="label-text font-bold text-theme-muted tracking-wider uppercase text-[10px]">Tool Compression (Chars)</span>
                      <span className="text-[9px] text-theme-muted opacity-70">Global Fallback if Blank</span>
                    </label>
                    <input
                      type="number"
                      name="compressionThreshold"
                      value={formData.compressionThreshold || ''}
                      onChange={(e) => setFormData(prev => ({ ...prev, compressionThreshold: e.target.value ? parseInt(e.target.value) : undefined }))}
                      className="input input-sm input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                      placeholder="e.g. 8000"
                    />
                  </div>
                  <div className="form-control">
                    <label className="label py-1 flex justify-between">
                      <span className="label-text font-bold text-theme-muted tracking-wider uppercase text-[10px]">Session Amnesia (Turns)</span>
                      <span className="text-[9px] text-theme-muted opacity-70">Global Fallback if Blank</span>
                    </label>
                    <input
                      type="number"
                      name="summarizationThreshold"
                      value={formData.summarizationThreshold || ''}
                      onChange={(e) => setFormData(prev => ({ ...prev, summarizationThreshold: e.target.value ? parseInt(e.target.value) : undefined }))}
                      className="input input-sm input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                      placeholder="e.g. 20"
                    />
                  </div>
                  <div className="form-control">
                    <label className="label py-1 flex justify-between">
                      <span className="label-text font-bold text-theme-muted tracking-wider uppercase text-[10px]">Optimization Model</span>
                      <span className="text-[9px] text-theme-muted opacity-70">Global Fallback if Blank</span>
                    </label>
                    <select
                      name="optimizationModelId"
                      value={formData.optimizationModelId || ''}
                      onChange={handleChange}
                      className="select select-sm select-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                    >
                      <option value="">(Platform Default Global Router)</option>
                      {availableModels.map(m => (
                          <option key={'opt_'+m.id} value={m.id}>
                              {m.name} ({m.provider}){m.available === false ? ' — unavailable' : ''}
                          </option>
                      ))}
                      {isEditing && !availableModels.find(m => m.id === formData.optimizationModelId) && formData.optimizationModelId && (
                          <option value={formData.optimizationModelId}>{formData.optimizationModelId} (Unregistered)</option>
                      )}
                    </select>
                  </div>
                </div>
              </>
            )}

            {/* ═══════════════════════════════════════
                TAB: SECURITY — PII, Tiers, Hooks
            ═══════════════════════════════════════ */}
            {activeTab === 'security' && (
              <>
                <SectionHeader title="Data Privacy & PII" />
                <div className="grid grid-cols-1 gap-3 w-full">
                  <div className="form-control w-full">
                    <label className="cursor-pointer p-4 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors w-full min-w-0 flex flex-col gap-3">
                      <div className="flex flex-col flex-1 min-w-0">
                        <span className="font-bold tracking-wider uppercase text-xs text-theme-foreground block">Enforce PII Redaction Filter</span>
                        <span className="text-xs text-theme-muted mt-1 block whitespace-normal wrap-break-word leading-relaxed">Automatically scrub SSN, Emails, and Phone Numbers from LLM payloads</span>
                      </div>
                      <input type="checkbox" name="requiresPiiRedaction" checked={formData.requiresPiiRedaction || false} onChange={handleChange} className="toggle toggle-warning" />
                    </label>
                  </div>

                  {/* Granular PII Policy Bindings — visible when PII redaction is enabled */}
                  {formData.requiresPiiRedaction && formData.agentId && (
                    <AgentPiiPolicySelector agentId={formData.agentId} />
                  )}
                </div>

                <SectionHeader title="Classification Tiers" />
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Security Tier</span></label>
                    <select
                      name="securityTier"
                      value={formData.securityTier ?? 1}
                      onChange={(e) => setFormData(prev => ({ ...prev, securityTier: Number(e.target.value) }))}
                      className="select select-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                    >
                      <option value={1}>Tier 1 — Read-only / Low Privilege</option>
                      <option value={2}>Tier 2 — Standard Operations</option>
                      <option value={3}>Tier 3 — Elevated / Requires Approval</option>
                    </select>
                    <span className="label-text-alt mt-1 text-theme-muted opacity-70 text-[10px]">Cross-tier Swarm handoffs to higher tiers trigger HITL approval</span>
                  </div>

                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Compliance & Data Privacy Tier</span></label>
                    <select
                      name="complianceTier"
                      value={formData.complianceTier ?? 'TIER_1_STANDARD'}
                      onChange={(e) => setFormData(prev => ({ ...prev, complianceTier: e.target.value as 'TIER_1_STANDARD' | 'TIER_2_STRICT' }))}
                      className="select select-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-warning"
                    >
                      <option value="TIER_1_STANDARD">Tier 1 — Standard (No Streaming Checks / No DB Encryption)</option>
                      <option value="TIER_2_STRICT">Tier 2 — Enterprise Strict (Sliding Buffer / DB AES-256)</option>
                    </select>
                    <span className="label-text-alt mt-1 text-theme-muted opacity-70 text-[10px]">Tier 2 introduces explicit TTFT latency by chunking SSE streams to catch PII leaks.</span>
                  </div>
                </div>

                <SectionHeader title="Deployment Status" />
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div className="form-control w-full">
                    <label className="cursor-pointer p-4 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors w-full min-w-0 h-full flex flex-col gap-3">
                      <div className="flex flex-col flex-1 min-w-0">
                        <span className="font-bold tracking-wider uppercase text-xs text-theme-foreground block">Production Approved</span>
                        <span className="text-xs text-theme-muted mt-1 block whitespace-normal wrap-break-word leading-relaxed">Flag agent as safe for end-user interaction outside Dev modes</span>
                      </div>
                      <input type="checkbox" name="approvedForProduction" checked={formData.approvedForProduction || false} onChange={handleChange} className="toggle toggle-success" />
                    </label>
                  </div>

                  <div className="form-control w-full">
                    <label className="cursor-pointer p-4 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors w-full min-w-0 h-full flex flex-col gap-3">
                      <div className="flex flex-col flex-1 min-w-0">
                        <span className="font-bold tracking-wider uppercase text-xs text-theme-foreground block">Maintenance Mode</span>
                        <span className="text-xs text-theme-muted mt-1 block whitespace-normal wrap-break-word leading-relaxed">Suspend execution via 503 HTTP Rejections</span>
                      </div>
                      <input type="checkbox" name="maintenanceMode" checked={formData.maintenanceMode || false} onChange={handleChange} className="toggle toggle-error" />
                    </label>
                  </div>
                </div>

                <SectionHeader title="Execution Hooks" />
                <div className="grid grid-cols-1 gap-4">
                  <AgentHookSelector
                    type="PRE"
                    selectedHookIds={formData.preHooks || []}
                    onChange={(hooks) => setFormData(p => ({...p, preHooks: hooks}))}
                  />
                  <AgentHookSelector
                    type="POST"
                    selectedHookIds={formData.postHooks || []}
                    onChange={(hooks) => setFormData(p => ({...p, postHooks: hooks}))}
                  />
                </div>

                <SectionHeader title="Agent Identity / Credentials" />
                {!isEditing ? (
                  <p className="text-xs text-theme-muted opacity-70">Save the agent first to manage credentials.</p>
                ) : (
                  <div className="space-y-3">
                    {/* Existing credentials list */}
                    {credLoading ? (
                      <div className="flex items-center gap-2 text-xs text-theme-muted"><span className="loading loading-spinner loading-xs"></span> Loading credentials...</div>
                    ) : credentials.length === 0 ? (
                      <p className="text-xs text-theme-muted opacity-70">No credentials configured. Add one below.</p>
                    ) : (
                      <div className="space-y-2">
                        {credentials.map(cred => (
                          <div key={cred.id} className="flex items-center justify-between gap-3 p-3 border border-obsidian-stroke rounded-lg bg-obsidian-surface">
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2">
                                <span className="font-bold text-sm text-theme-foreground">{cred.providerName}</span>
                                <span className="badge badge-xs badge-outline font-mono">{cred.credentialType}</span>
                                <span className={`badge badge-xs ${cred.enabled ? 'badge-success' : 'badge-error'}`}>{cred.enabled ? 'Active' : 'Disabled'}</span>
                              </div>
                              {cred.scopes && <span className="text-[10px] text-theme-muted mt-0.5 block truncate">Scopes: {cred.scopes}</span>}
                            </div>
                            <button type="button" className="btn btn-xs btn-ghost text-error hover:bg-error/10" onClick={() => cred.id && handleDeleteCredential(cred.id)}>Delete</button>
                          </div>
                        ))}
                      </div>
                    )}

                    {/* Add new credential form */}
                    <div className="p-4 border border-dashed border-obsidian-stroke rounded-lg bg-obsidian-surface/50 space-y-3">
                      <span className="font-bold tracking-wider uppercase text-[10px] text-theme-muted">Add Credential</span>
                      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                        <div className="form-control">
                          <label className="label py-0.5"><span className="label-text text-[10px] font-bold text-theme-muted uppercase">Provider</span></label>
                          <input type="text" value={newCred.providerName || ''} onChange={e => setNewCred(p => ({ ...p, providerName: e.target.value }))} placeholder="e.g. stripe" className="input input-sm input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue" />
                        </div>
                        <div className="form-control">
                          <label className="label py-0.5"><span className="label-text text-[10px] font-bold text-theme-muted uppercase">Type</span></label>
                          <select value={newCred.credentialType || 'API_KEY'} onChange={e => setNewCred(p => ({ ...p, credentialType: e.target.value }))} className="select select-sm select-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue">
                            <option value="API_KEY">API Key</option>
                            <option value="BEARER">Bearer Token</option>
                            <option value="OAUTH2">OAuth2 Client Credentials</option>
                          </select>
                        </div>
                        <div className="form-control">
                          <label className="label py-0.5"><span className="label-text text-[10px] font-bold text-theme-muted uppercase">Secret / Key</span></label>
                          <input type="password" {...NO_AUTOFILL} value={newCred.encryptedSecret || ''} onChange={e => setNewCred(p => ({ ...p, encryptedSecret: e.target.value }))} placeholder="sk-..." className="input input-sm input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue" />
                        </div>
                      </div>
                      {newCred.credentialType === 'OAUTH2' && (
                        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                          <div className="form-control">
                            <label className="label py-0.5"><span className="label-text text-[10px] font-bold text-theme-muted uppercase">Token Endpoint</span></label>
                            <input type="text" value={newCred.tokenEndpoint || ''} onChange={e => setNewCred(p => ({ ...p, tokenEndpoint: e.target.value }))} placeholder="https://oauth.example.com/token" className="input input-sm input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue" />
                          </div>
                          <div className="form-control">
                            <label className="label py-0.5"><span className="label-text text-[10px] font-bold text-theme-muted uppercase">Client ID</span></label>
                            <input type="text" value={newCred.clientId || ''} onChange={e => setNewCred(p => ({ ...p, clientId: e.target.value }))} placeholder="client_abc123" className="input input-sm input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue" />
                          </div>
                          <div className="form-control">
                            <label className="label py-0.5"><span className="label-text text-[10px] font-bold text-theme-muted uppercase">Scopes</span></label>
                            <input type="text" value={newCred.scopes || ''} onChange={e => setNewCred(p => ({ ...p, scopes: e.target.value }))} placeholder="read,write" className="input input-sm input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue" />
                          </div>
                        </div>
                      )}
                      <button type="button" className="btn btn-sm bg-agent-blue hover:bg-agent-blue/80 text-white border-0" onClick={handleAddCredential} disabled={!newCred.providerName || !newCred.encryptedSecret}>
                        Add Credential
                      </button>
                    </div>
                  </div>
                )}
              </>
            )}

            {/* ═══════════════════════════════════════
                TAB: DOCS & QA — Meta-data & Certification
            ═══════════════════════════════════════ */}
            {activeTab === 'docs' && (
              <>
                <SectionHeader title="Documentation & Standards" />
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Markdown Documentation Template</span></label>
                    <input
                      type="text"
                      name="markdownDocs"
                      value={formData.markdownDocs || ''}
                      onChange={handleChange}
                      className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                      placeholder="e.g. /docs/agents/support.md"
                    />
                  </div>
                  <div className="form-control">
                    <label className="label py-1"><span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Accessibility Compatibility</span></label>
                    <input
                      type="text"
                      name="accessibilityCompatibility"
                      value={formData.accessibilityCompatibility || ''}
                      onChange={handleChange}
                      className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
                      placeholder="e.g. WCAG 2.1 AA"
                    />
                  </div>
                </div>

                <SectionHeader title="Localization & Validation" />
                <div className="form-control">
                  <MultiSelectDropdown
                    label="Supported Locales"
                    value={formData.supportedLocales || []}
                    onChange={(val) => setFormData(prev => ({ ...prev, supportedLocales: val }))}
                    options={[
                      { value: 'en-US', label: 'English (US)' },
                      { value: 'en-GB', label: 'English (UK)' },
                      { value: 'es-ES', label: 'Spanish (Spain)' },
                      { value: 'es-MX', label: 'Spanish (Mexico)' },
                      { value: 'fr-FR', label: 'French (France)' },
                      { value: 'de-DE', label: 'German (Germany)' },
                      { value: 'it-IT', label: 'Italian (Italy)' },
                      { value: 'pt-BR', label: 'Portuguese (Brazil)' },
                      { value: 'ja-JP', label: 'Japanese (Japan)' },
                      { value: 'ko-KR', label: 'Korean (Korea)' },
                      { value: 'zh-CN', label: 'Chinese (Simplified)' },
                      { value: 'zh-TW', label: 'Chinese (Traditional)' },
                      { value: 'ar-SA', label: 'Arabic (Saudi Arabia)' },
                      { value: 'hi-IN', label: 'Hindi (India)' },
                      { value: 'nl-NL', label: 'Dutch (Netherlands)' },
                      { value: 'sv-SE', label: 'Swedish (Sweden)' },
                    ]}
                    placeholder="Select supported locales..."
                    emptyMessage="No locales available"
                  />
                </div>
                <div className="form-control mt-4">
                  <MultiSelectDropdown
                    label="Validation Training Datasets"
                    value={formData.trainingDatasets || []}
                    onChange={(val) => setFormData(prev => ({ ...prev, trainingDatasets: val }))}
                    options={(formData.trainingDatasets || []).map(d => ({ value: d, label: d }))}
                    placeholder="No training datasets configured"
                    emptyMessage="No datasets available"
                    description="Training datasets are managed externally. Existing bindings are shown."
                  />
                </div>
              </>
            )}
          </div>

          {/* --- Footer Actions --- */}
          <div className="modal-action pt-4 mt-4 border-t border-obsidian-stroke flex justify-end gap-3 shrink-0">
            <button type="button" className="btn btn-ghost text-theme-muted hover:text-white" onClick={guardedClose} disabled={loading}>
              Cancel
            </button>
            <button type="submit" className="btn bg-agent-blue hover:bg-agent-blue/80 text-white border-0 min-w-30" disabled={loading}>
              {loading ? <span className="loading loading-spinner loading-sm"></span> : 'Save Configuration'}
            </button>
          </div>
        </form>
        )}
      </div>
      <div className="modal-backdrop" onClick={guardedClose}></div>

      {/* Unsaved Changes Confirmation */}
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
