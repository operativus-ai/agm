import React, { useEffect, useState, useRef } from 'react';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import type { WorkflowStep, WorkflowTemplate, WorkflowStepType } from '../../../shared/types/orchestration';
import type { AgentConfig } from '../../../shared/types/api';
import { Typography } from '../../../shared/components/ui/Typography';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import { Badge } from '../../../shared/components/ui/Badge';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { configApi } from '../../../shared/api/configApi';
import { TemplatePickerGrid } from '../../../shared/components/ui/TemplatePickerGrid';
import type { TemplateCardItem } from '../../../shared/components/ui/TemplatePickerGrid';

const WF_TEMPLATE_ICONS: Record<string, string> = {
  search:            '\u{1F50D}',
  'file-text':       '\u{1F4DD}',
  database:          '\u{1F5C4}\u{FE0F}',
  headphones:        '\u{1F3A7}',
  'shield-check':    '\u{1F6E1}\u{FE0F}',
  settings:          '\u{2699}\u{FE0F}',
  'clipboard-check': '\u{1F4CB}',
  'book-open':       '\u{1F4D6}',
  activity:          '\u{1F4C8}',
  'git-merge':       '\u{1F500}',
  rocket:            '\u{1F680}',
};
import { AgentsApi } from '../../agents/api/agents-api';
import { useParams, useNavigate, useBlocker } from 'react-router-dom';
import {
  LuWaypoints, LuPlus, LuTrash2, LuBot, LuGitBranch, LuGlobe, LuRepeat, LuGitMerge,
  LuArrowDown, LuChevronDown, LuChevronUp, LuSave, LuCopy, LuTriangleAlert, LuArrowLeft,
  LuHistory,
} from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { Tooltip } from '../../../shared/components/ui/Tooltip';

interface LocalStep {
  localId: string; // client-side key (UUID or existing server id)
  serverId?: string; // if persisted, the real id
  stepOrder: number;
  stepType: WorkflowStepType;
  agentId: string;
  action: string;
  label?: string; // from template
}

let _localIdCounter = 0;
const newLocalId = () => `local-${++_localIdCounter}-${Date.now()}`;

export const WorkflowEditorPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isNew = !id || id === 'new';

  // Workflow metadata
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [workflowId, setWorkflowId] = useState<string | null>(null);

  // Steps
  const [steps, setSteps] = useState<LocalStep[]>([]);
  const [originalSteps, setOriginalSteps] = useState<LocalStep[]>([]);

  // Agents for dropdown
  const [agents, setAgents] = useState<AgentConfig[]>([]);

  // UI state
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [leaveOpen, setLeaveOpen] = useState(false);
  const [wizardStep, setWizardStep] = useState<'template' | 'form'>(isNew ? 'template' : 'form');
  const [wfTemplates, setWfTemplates] = useState<WorkflowTemplate[]>([]);
  const [wfTemplatesLoading, setWfTemplatesLoading] = useState(false);
  const [wfTemplatesError, setWfTemplatesError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const [stepErrors, setStepErrors] = useState<Record<string, string>>({});
  const [nameError, setNameError] = useState<string | null>(null);
  const pendingNavigate = useRef<(() => void) | null>(null);

  // Navigation guard — warn on unsaved changes
  const blocker = useBlocker(dirty);

  useEffect(() => {
    if (blocker.state === 'blocked') {
      setLeaveOpen(true);
      pendingNavigate.current = () => blocker.proceed();
    }
  }, [blocker.state]);

  // Load agents + workflow
  useEffect(() => {
    AgentsApi.getAgents().then(setAgents).catch(console.error);

    if (isNew) {
      setWfTemplatesLoading(true);
      configApi.getWorkflowTemplates()
        .then(setWfTemplates)
        .catch(() => setWfTemplatesError('Failed to load templates. You can still create a blank workflow.'))
        .finally(() => setWfTemplatesLoading(false));
      setLoading(false);
    } else {
      loadWorkflow(id!);
    }
  }, [id]);

  const loadWorkflow = async (wfId: string) => {
    try {
      setLoading(true);
      const data = await orchestrationApi.getWorkflow(wfId);
      setWorkflowId(data.id);
      setName(data.name || '');
      setDescription(data.description || '');

      const sortedSteps = (data.steps || [])
        .sort((a, b) => (a.stepOrder || 0) - (b.stepOrder || 0));
      const locals: LocalStep[] = sortedSteps.map((s, i) => ({
        localId: s.id,
        serverId: s.id,
        stepOrder: i + 1,
        stepType: (s.action?.toUpperCase() as WorkflowStepType) || 'AGENT',
        agentId: s.agentId || '',
        action: s.stepType === 'CONDITION' || s.action?.toUpperCase() === 'CONDITION' ? s.agentId || '' : s.action || '',
        label: undefined,
      }));
      setSteps(locals);
      setOriginalSteps(locals);
    } catch (err) {
      console.error('Failed to load workflow', err);
      setError('Failed to load workflow.');
    } finally {
      setLoading(false);
    }
  };

  // Template selection
  const handleTemplateSelect = (tpl: WorkflowTemplate) => {
    setWizardStep('form');
    if (tpl.id === 'custom') {
      return;
    }
    setName(tpl.name);
    setDescription(tpl.description);
    const locals: LocalStep[] = tpl.steps.map(s => ({
      localId: newLocalId(),
      stepOrder: s.stepOrder,
      stepType: s.stepType as WorkflowStepType,
      agentId: '',
      action: s.action,
      label: s.label,
    }));
    setSteps(locals);
    setDirty(true);
  };

  // Step mutations
  const updateStep = (localId: string, field: keyof LocalStep, value: string) => {
    setSteps(prev => prev.map(s => s.localId === localId ? { ...s, [field]: value } : s));
    setDirty(true);
  };

  const addStep = (type: WorkflowStepType = 'AGENT') => {
    setSteps(prev => [
      ...prev,
      {
        localId: newLocalId(),
        stepOrder: prev.length + 1,
        stepType: type,
        agentId: '',
        action: '',
      },
    ]);
    setDirty(true);
  };

  const removeStep = (localId: string) => {
    setSteps(prev => prev.filter(s => s.localId !== localId).map((s, i) => ({ ...s, stepOrder: i + 1 })));
    setDirty(true);
  };

  const moveStep = (localId: string, direction: 'up' | 'down') => {
    setSteps(prev => {
      const idx = prev.findIndex(s => s.localId === localId);
      if (idx < 0) return prev;
      const targetIdx = direction === 'up' ? idx - 1 : idx + 1;
      if (targetIdx < 0 || targetIdx >= prev.length) return prev;
      const next = [...prev];
      [next[idx], next[targetIdx]] = [next[targetIdx], next[idx]];
      return next.map((s, i) => ({ ...s, stepOrder: i + 1 }));
    });
    setDirty(true);
  };

  // Validate all fields before save
  const validate = (): boolean => {
    let valid = true;
    const errors: Record<string, string> = {};

    if (!name.trim()) {
      setNameError('Workflow name is required.');
      valid = false;
    } else {
      setNameError(null);
    }

    for (const step of steps) {
      if ((step.stepType === 'AGENT' || step.stepType === 'PARALLEL') && !step.agentId) {
        errors[step.localId] = 'Select an agent for this step.';
        valid = false;
      } else if (step.stepType === 'CONDITION' && !step.action.trim()) {
        errors[step.localId] = 'Condition expression is required.';
        valid = false;
      } else if (step.stepType === 'WEBHOOK' && !step.agentId?.trim()) {
        errors[step.localId] = 'Webhook URL is required.';
        valid = false;
      } else if (step.stepType === 'LOOP' && !step.agentId?.trim()) {
        errors[step.localId] = 'Loop configuration is required (e.g., max:5|until:contains:done).';
        valid = false;
      }
    }

    setStepErrors(errors);
    if (!valid) {
      setError('Please fix the highlighted fields before saving.');
    }
    return valid;
  };

  // Save
  const handleSave = async () => {
    if (!validate()) return;
    try {
      setSaving(true);
      setError(null);
      setSuccess(null);

      // 1. Save/update workflow metadata
      let wfId = workflowId;
      if (wfId) {
        await orchestrationApi.updateWorkflow(wfId, { name, description });
      } else {
        const created = await orchestrationApi.createWorkflow({ name, description, status: 'DRAFT' });
        wfId = created.id;
        setWorkflowId(wfId);
      }

      // 2. Diff steps: remove old ones that changed or were deleted, add new/modified
      const toRemove = originalSteps.filter(orig => {
        const current = steps.find(s => s.serverId && s.serverId === orig.serverId);
        if (!current) return true; // deleted
        return current.agentId !== orig.agentId || current.action !== orig.action || current.stepOrder !== orig.stepOrder || current.stepType !== orig.stepType;
      });

      const toAdd = steps.filter(s => {
        if (!s.serverId) return true; // new step
        const orig = originalSteps.find(o => o.serverId === s.serverId);
        if (!orig) return true;
        return s.agentId !== orig.agentId || s.action !== orig.action || s.stepOrder !== orig.stepOrder || s.stepType !== orig.stepType;
      });

      for (const rm of toRemove) {
        if (rm.serverId) {
          await orchestrationApi.removeWorkflowStep(wfId!, rm.serverId);
        }
      }

      for (const add of toAdd) {
        const stepData: Partial<WorkflowStep> = {
          workflowId: wfId!,
          stepOrder: add.stepOrder,
          agentId: add.stepType === 'CONDITION' ? add.action : (add.agentId || undefined),
          action: add.stepType === 'CONDITION' ? 'CONDITION' : (add.action || undefined),
        };
        await orchestrationApi.addWorkflowStep(wfId!, stepData);
      }

      // 3. Reload to sync server state
      await loadWorkflow(wfId!);
      setDirty(false);
      setSuccess('Workflow saved successfully.');

      // If this was a new workflow, update URL without full reload
      if (isNew) {
        window.history.replaceState(null, '', `/workflows/${wfId}`);
      }
    } catch (err: any) {
      console.error('Save failed', err);
      setError(err?.message || 'Failed to save workflow.');
    } finally {
      setSaving(false);
    }
  };

  // Delete
  const handleDelete = async () => {
    if (!workflowId) return;
    try {
      await orchestrationApi.deleteWorkflow(workflowId);
      navigate('/workflows');
    } catch (err) {
      console.error('Delete failed', err);
      setError('Failed to delete workflow.');
    }
  };

  // Clone
  const handleClone = async () => {
    if (!workflowId) return;
    try {
      const cloned = await orchestrationApi.cloneWorkflow(workflowId);
      navigate(`/workflows/${cloned.id}`);
    } catch (err) {
      console.error('Clone failed', err);
    }
  };

  if (loading) {
    return (
      <div className="space-y-2">
        {[1, 2, 3, 4].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
      </div>
    );
  }

  // ── Template Picker Step (new workflows only) ────────────────
  if (wizardStep === 'template' && isNew) {
    return (
      <PageContainer variant="form">
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="sm" className="shrink-0" onClick={() => navigate('/workflows')}>
            <LuArrowLeft className="w-4 h-4" />
          </Button>
          <PageHeader
            icon={LuWaypoints}
            title="Choose a Workflow Template"
            subtitle="Select a pre-configured template to get started quickly, or start from scratch."
          />
        </div>

        {wfTemplatesError && (
          <Alert severity="warning">{wfTemplatesError}</Alert>
        )}

        <TemplatePickerGrid
          items={wfTemplates.map((tpl): TemplateCardItem => ({
            id: tpl.id,
            icon: WF_TEMPLATE_ICONS[tpl.icon] || '\u{1F4E6}',
            name: tpl.name,
            description: tpl.description,
            metadata: tpl.steps.length > 0
              ? `${tpl.steps.length} step${tpl.steps.length !== 1 ? 's' : ''}`
              : undefined,
          }))}
          onSelect={(id) => {
            const tpl = wfTemplates.find(t => t.id === id);
            if (tpl) handleTemplateSelect(tpl);
          }}
          loading={wfTemplatesLoading}
        />
      </PageContainer>
    );
  }

  return (
    <PageContainer variant="form">
      {/* Header */}
      <div className="flex items-center gap-4">
        <Button
          variant="ghost"
          size="sm"
          className="shrink-0"
          onClick={() => navigate('/workflows')}
        >
          <LuArrowLeft className="w-4 h-4" />
        </Button>
        <PageHeader
          icon={LuWaypoints}
          title={isNew && !workflowId ? 'New Workflow' : 'Edit Workflow'}
          subtitle={dirty ? 'Unsaved changes — Define sequential agent steps' : 'Define sequential agent steps'}
          actions={
            <>
              {isNew && wfTemplates.length > 0 && (
                <Button variant="ghost" size="sm" className="gap-1.5" onClick={() => { setWizardStep('template'); setDirty(false); }}>
                  <LuWaypoints className="w-3.5 h-3.5" /> Templates
                </Button>
              )}
              {workflowId && (
                <>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="gap-1.5"
                    onClick={() => navigate(`/workflows/${workflowId}/graph`)}
                    title="Open the graph editor"
                  >
                    <LuWaypoints className="w-4 h-4" /> Graph editor
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="gap-1.5"
                    onClick={() => navigate(`/workflows/${workflowId}/runs`)}
                    title="View run history"
                  >
                    <LuHistory className="w-4 h-4" /> Run history
                  </Button>
                  <Button variant="ghost" size="sm" className="gap-1.5" onClick={handleClone} title="Clone workflow">
                    <LuCopy className="w-4 h-4" /> Clone
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="gap-1.5 text-error hover:bg-error/10"
                    onClick={() => setDeleteOpen(true)}
                    title="Delete workflow"
                  >
                    <LuTrash2 className="w-4 h-4" /> Delete
                  </Button>
                </>
              )}
              <Button
                size="sm"
                className="gap-1.5"
                onClick={handleSave}
                disabled={saving}
              >
                {saving ? <span className="loading loading-spinner loading-xs" /> : <LuSave className="w-4 h-4" />}
                Save
              </Button>
            </>
          }
        />
      </div>

      {/* Alerts */}
      {error && <Alert severity="error" title="Error" description={error} dismissible onClose={() => setError(null)} />}
      {success && <Alert severity="success" title="Saved" description={success} dismissible onClose={() => setSuccess(null)} />}

      {/* Workflow Metadata */}
      <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-5 space-y-4">
        <div className="form-control">
          <label className="label"><span className="label-text font-medium">Workflow Name</span></label>
          <input
            type="text"
            className={`input input-bordered input-sm focus:input-primary ${nameError ? 'input-error' : ''}`}
            placeholder="e.g., Research Pipeline"
            value={name}
            onChange={e => { setName(e.target.value); setNameError(null); setDirty(true); }}
          />
          {nameError && <label className="label"><span className="label-text-alt text-error">{nameError}</span></label>}
        </div>
        <div className="form-control">
          <label className="label"><span className="label-text font-medium">Description</span></label>
          <textarea
            className="textarea textarea-bordered textarea-sm h-16 focus:textarea-primary"
            placeholder="What does this workflow do?"
            value={description}
            onChange={e => { setDescription(e.target.value); setDirty(true); }}
          />
        </div>
      </div>

      {/* Steps */}
      <div>
        <div className="flex items-center justify-between mb-3">
          <Typography.Heading level={4} className="flex items-center gap-2">
            Steps
            <Badge variant="neutral" className="text-xs font-mono">{steps.length}</Badge>
          </Typography.Heading>
        </div>

        {steps.length === 0 ? (
          <div className="border-2 border-dashed border-(--theme-muted)/20 rounded-xl p-8 text-center">
            <LuWaypoints className="w-8 h-8 mx-auto text-(--theme-muted) mb-2" />
            <p className="text-sm text-(--theme-muted) mb-3">No steps yet. Add your first step or pick a template.</p>
            <div className="flex gap-2 justify-center">
              <Button size="sm" className="gap-1.5" onClick={() => addStep('AGENT')}>
                <LuPlus className="w-4 h-4" /> Add Agent Step
              </Button>
              <Button variant="ghost" size="sm" className="gap-1.5" onClick={() => setWizardStep('template')}>
                Use Template
              </Button>
            </div>
          </div>
        ) : (
          <div className="space-y-0">
            {steps.map((step, idx) => (
              <React.Fragment key={step.localId}>
                <StepCard
                  step={step}
                  index={idx}
                  total={steps.length}
                  agents={agents}
                  error={stepErrors[step.localId]}
                  onUpdate={(lid, field, value) => { updateStep(lid, field, value); setStepErrors(prev => { const n = {...prev}; delete n[lid]; return n; }); }}
                  onRemove={removeStep}
                  onMove={moveStep}
                />
                {idx < steps.length - 1 && (
                  <div className="flex justify-center py-1">
                    <LuArrowDown className="w-4 h-4 text-(--theme-muted)" />
                  </div>
                )}
              </React.Fragment>
            ))}
          </div>
        )}

        {/* Add Step Buttons */}
        {steps.length > 0 && (
          <div className="flex gap-2 mt-4 flex-wrap">
            <Tooltip label="Run an agent with specific instructions on the workflow context">
              <Button variant="outline" size="sm" className="gap-1.5" onClick={() => addStep('AGENT')}>
                <LuBot className="w-3.5 h-3.5" /> Agent Step
              </Button>
            </Tooltip>
            <Tooltip label="Skip the next step if a condition is not met (e.g., contains:urgent)">
              <Button variant="outline" size="sm" className="gap-1.5" onClick={() => addStep('CONDITION')}>
                <LuGitBranch className="w-3.5 h-3.5" /> Condition
              </Button>
            </Tooltip>
            <Tooltip label="Run the next step concurrently alongside other parallel steps">
              <Button variant="outline" size="sm" className="gap-1.5" onClick={() => addStep('PARALLEL')}>
                <LuGitMerge className="w-3.5 h-3.5" /> Parallel
              </Button>
            </Tooltip>
            <Tooltip label="POST workflow context as JSON to an external HTTP endpoint">
              <Button variant="outline" size="sm" className="gap-1.5" onClick={() => addStep('WEBHOOK')}>
                <LuGlobe className="w-3.5 h-3.5" /> Webhook
              </Button>
            </Tooltip>
            <Tooltip label="Repeat the next step N times or until a condition is satisfied">
              <Button variant="outline" size="sm" className="gap-1.5" onClick={() => addStep('LOOP')}>
                <LuRepeat className="w-3.5 h-3.5" /> Loop
              </Button>
            </Tooltip>
          </div>
        )}
      </div>

      {/* Delete Dialog */}
      <Dialog
        isOpen={deleteOpen}
        setIsOpen={setDeleteOpen}
        title="Delete Workflow"
        content={`Permanently delete "${name}"? This removes all steps and run history.`}
        severity="error"
        confirmLabel="Delete"
        onConfirm={handleDelete}
      />

      {/* Unsaved Changes Dialog */}
      <Dialog
        isOpen={leaveOpen}
        setIsOpen={(open) => {
          setLeaveOpen(open);
          if (!open && blocker.state === 'blocked') blocker.reset();
        }}
        title="Unsaved Changes"
        content="You have unsaved changes. Are you sure you want to leave? Changes will be lost."
        severity="warning"
        confirmLabel="Leave"
        cancelLabel="Stay"
        onConfirm={() => {
          setLeaveOpen(false);
          setDirty(false);
          pendingNavigate.current?.();
        }}
        onCancel={() => {
          setLeaveOpen(false);
          if (blocker.state === 'blocked') blocker.reset();
        }}
      />
    </PageContainer>
  );
};

// ── Step Card Component ──────────────────────────────────────

interface StepCardProps {
  step: LocalStep;
  index: number;
  total: number;
  agents: AgentConfig[];
  error?: string;
  onUpdate: (localId: string, field: keyof LocalStep, value: string) => void;
  onRemove: (localId: string) => void;
  onMove: (localId: string, direction: 'up' | 'down') => void;
}

const STEP_TYPE_LABELS: Record<string, { label: string; icon: React.ReactNode; color: string }> = {
  AGENT:     { label: 'Agent',     icon: <LuBot className="w-4 h-4" />,       color: 'text-primary' },
  CONDITION: { label: 'Condition', icon: <LuGitBranch className="w-4 h-4" />, color: 'text-warning' },
  PARALLEL:  { label: 'Parallel',  icon: <LuGitMerge className="w-4 h-4" />,  color: 'text-info' },
  WEBHOOK:   { label: 'Webhook',   icon: <LuGlobe className="w-4 h-4" />,     color: 'text-success' },
  LOOP:      { label: 'Loop',      icon: <LuRepeat className="w-4 h-4" />,    color: 'text-secondary' },
};

const StepCard: React.FC<StepCardProps> = ({ step, index, total, agents, error, onUpdate, onRemove, onMove }) => {
  const meta = STEP_TYPE_LABELS[step.stepType] || STEP_TYPE_LABELS.AGENT;
  const hasError = !!error;

  return (
    <div className={`bg-(--theme-card) border rounded-xl transition-colors ${hasError ? 'border-error/50' : 'border-(--theme-muted)/10 hover:border-primary/30'}`}>
      <div className="p-4">
        {/* Header row */}
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <span className="font-mono text-xs bg-obsidian-elevated px-2 py-0.5 rounded text-(--theme-muted)">
              {index + 1}
            </span>
            <span className={`flex items-center gap-1 text-xs font-medium ${meta.color}`}>
              {meta.icon} {meta.label}
            </span>
            {step.label && (
              <span className="text-xs text-(--theme-muted) italic">{step.label}</span>
            )}
          </div>
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="sm"
              className="px-1.5"
              onClick={() => onMove(step.localId, 'up')}
              disabled={index === 0}
              title="Move up"
            >
              <LuChevronUp className="w-3.5 h-3.5" />
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className="px-1.5"
              onClick={() => onMove(step.localId, 'down')}
              disabled={index === total - 1}
              title="Move down"
            >
              <LuChevronDown className="w-3.5 h-3.5" />
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className="px-1.5 text-error hover:bg-error/10"
              onClick={() => onRemove(step.localId)}
              title="Remove step"
            >
              <LuTrash2 className="w-3.5 h-3.5" />
            </Button>
          </div>
        </div>

        {/* Body — depends on step type */}
        {step.stepType === 'CONDITION' ? (
          <div className="form-control">
            <label className="label"><span className="label-text text-xs">Condition Expression</span></label>
            <input
              type="text"
              className={`input input-bordered input-sm font-mono ${hasError ? 'input-error' : ''}`}
              placeholder="e.g., contains:urgent, length>100, not_empty"
              value={step.action}
              onChange={e => onUpdate(step.localId, 'action', e.target.value)}
            />
            <label className="label">
              <span className="label-text-alt text-(--theme-muted)">
                If false, the next step is skipped
              </span>
            </label>
          </div>
        ) : step.stepType === 'WEBHOOK' ? (
          <div className="form-control">
            <label className="label"><span className="label-text text-xs">Webhook URL</span></label>
            <input
              type="url"
              className={`input input-bordered input-sm font-mono ${hasError ? 'input-error' : ''}`}
              placeholder="https://api.example.com/webhook"
              value={step.agentId}
              onChange={e => onUpdate(step.localId, 'agentId', e.target.value)}
            />
            <label className="label">
              <span className="label-text-alt text-(--theme-muted)">
                HTTP POST with workflow context as JSON payload
              </span>
            </label>
          </div>
        ) : step.stepType === 'LOOP' ? (
          <div className="form-control">
            <label className="label"><span className="label-text text-xs">Loop Configuration</span></label>
            <input
              type="text"
              className={`input input-bordered input-sm font-mono ${hasError ? 'input-error' : ''}`}
              placeholder="e.g., max:5|until:contains:done"
              value={step.agentId}
              onChange={e => onUpdate(step.localId, 'agentId', e.target.value)}
            />
            <label className="label">
              <span className="label-text-alt text-(--theme-muted)">
                Repeats the next step. Format: max:N|until:condition_expr
              </span>
            </label>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="form-control">
              <label className="label"><span className="label-text text-xs">Agent</span></label>
              <select
                className={`select select-bordered select-sm ${hasError ? 'select-error' : ''}`}
                value={step.agentId}
                onChange={e => onUpdate(step.localId, 'agentId', e.target.value)}
              >
                <option value="">-- Select Agent --</option>
                {agents.map(a => (
                  <option key={a.agentId} value={a.agentId}>
                    {a.name || a.agentId}
                  </option>
                ))}
              </select>
            </div>
            <div className="form-control">
              <label className="label"><span className="label-text text-xs">Action / Instructions</span></label>
              <input
                type="text"
                className="input input-bordered input-sm"
                placeholder="What should this agent do?"
                value={step.action}
                onChange={e => onUpdate(step.localId, 'action', e.target.value)}
              />
              {step.stepType === 'PARALLEL' && (
                <label className="label">
                  <span className="label-text-alt text-(--theme-muted)">
                    Steps at the same position run concurrently
                  </span>
                </label>
              )}
            </div>
          </div>
        )}

        {/* Inline validation error */}
        {hasError && (
          <div className="flex items-center gap-1.5 mt-2 text-error text-xs">
            <LuTriangleAlert className="w-3.5 h-3.5 shrink-0" />
            <span>{error}</span>
          </div>
        )}
      </div>
    </div>
  );
};
